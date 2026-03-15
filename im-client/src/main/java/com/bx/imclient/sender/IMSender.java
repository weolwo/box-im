package com.bx.imclient.sender;

import cn.hutool.core.collection.CollUtil;
import com.bx.imclient.listener.MessageListenerMulticaster;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.enums.IMSendCode;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.*;
import com.bx.imcommon.mq.RedisMQTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IMSender {

    private final RedisMQTemplate redisMQTemplate;
    private final MessageListenerMulticaster listenerMulticaster;

    @Value("${spring.application.name}")
    private String appName;

    // ================== 【核心方法】系统消息 ==================
    public <T> void sendSystemMessage(IMSystemMessage<T> message) {
        //直接复用 Pipeline 批量查询引擎，干掉原来的 String 拼接和循环！
        Map<Long, Map<Object, Object>> allOnlineStatus = getAllOnlineStatus(message.getRecvIds());

        Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>();
        List<IMUserInfo> offLineUsers = new LinkedList<>();

        for (Long userId : message.getRecvIds()) {
            Map<Object, Object> terminals = allOnlineStatus.get(userId);
            for (Integer reqTerminal : message.getRecvTerminals()) {
                IMUserInfo userInfo = new IMUserInfo(userId, reqTerminal);
                // 判断该用户对应的终端是否在线
                if (terminals != null && terminals.containsKey(reqTerminal.toString())) {
                    Integer serverId = (Integer) terminals.get(reqTerminal.toString());
                    serverMap.computeIfAbsent(serverId, k -> new LinkedList<>()).add(userInfo);
                } else {
                    offLineUsers.add(userInfo);
                }
            }
        }

        // 逐个server发送 (推送至 Redis List 队列)
        serverMap.forEach((serverId, receivers) -> {
            IMRecvInfo recvInfo = new IMRecvInfo();
            recvInfo.setCmd(IMCmdType.SYSTEM_MESSAGE.code());
            recvInfo.setReceivers(receivers);
            recvInfo.setServiceName(appName);
            recvInfo.setSendResult(message.getSendResult());
            recvInfo.setData(message.getData());

            String key = String.join(":", IMRedisKey.IM_MESSAGE_SYSTEM_QUEUE, serverId.toString());
            redisMQTemplate.opsForList().rightPush(key, recvInfo);
        });

        // 离线回复
        if (message.getSendResult() && !offLineUsers.isEmpty()) {
            List<IMSendResult> results = offLineUsers.stream().map(u -> {
                IMSendResult result = new IMSendResult();
                result.setReceiver(u);
                result.setCode(IMSendCode.NOT_ONLINE.code());
                result.setData(message.getData());
                return result;
            }).collect(Collectors.toList());
            listenerMulticaster.multicast(IMListenerType.SYSTEM_MESSAGE, results);
        }
    }

    // ================== 【核心方法】私聊消息 ==================
    public <T> void sendPrivateMessage(IMPrivateMessage<T> message) {
        List<IMSendResult> results = new LinkedList<>();

        if (!Objects.isNull(message.getRecvId())) {
            // 满分重构：私聊给单人发，直接利用 opsForHash 里的 hmget，一次性拉取对方所需的多个终端状态！
            String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, message.getRecvId().toString());
            List<Object> hashKeys = message.getRecvTerminals().stream().map(Object::toString).collect(Collectors.toList());
            List<Object> serverIds = redisMQTemplate.opsForHash().multiGet(key, hashKeys);

            for (int i = 0; i < message.getRecvTerminals().size(); i++) {
                Integer terminal = message.getRecvTerminals().get(i);
                Integer serverId = (Integer) serverIds.get(i);

                if (serverId != null) {
                    String sendKey = String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString());
                    IMRecvInfo recvInfo = new IMRecvInfo();
                    recvInfo.setCmd(IMCmdType.PRIVATE_MESSAGE.code());
                    recvInfo.setSendResult(message.getSendResult());
                    recvInfo.setServiceName(appName);
                    recvInfo.setSender(message.getSender());
                    recvInfo.setReceivers(Collections.singletonList(new IMUserInfo(message.getRecvId(), terminal)));
                    recvInfo.setData(message.getData());
                    redisMQTemplate.opsForList().rightPush(sendKey, recvInfo);
                } else {
                    IMSendResult result = new IMSendResult();
                    result.setSender(message.getSender());
                    result.setReceiver(new IMUserInfo(message.getRecvId(), terminal));
                    result.setCode(IMSendCode.NOT_ONLINE.code());
                    result.setData(message.getData());
                    results.add(result);
                }
            }
        }

        // 推送给自己的其他终端 (SendToSelf)
        if (message.getSendToSelf()) {
            // 满分重构：查自己其他终端，也改用 Hash 结构
            String selfKey = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION,message.getSender().getId().toString());
            for (Integer terminal : IMTerminalType.codes()) {
                if (message.getSender().getTerminal().equals(terminal)) continue;

                Integer serverId = (Integer) redisMQTemplate.opsForHash().get(selfKey, terminal.toString());
                if (serverId != null) {
                    String sendKey = String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString());
                    IMRecvInfo recvInfo = new IMRecvInfo();
                    recvInfo.setSendResult(false);
                    recvInfo.setCmd(IMCmdType.PRIVATE_MESSAGE.code());
                    recvInfo.setSender(message.getSender());
                    recvInfo.setReceivers(Collections.singletonList(new IMUserInfo(message.getSender().getId(), terminal)));
                    recvInfo.setData(message.getData());
                    redisMQTemplate.opsForList().rightPush(sendKey, recvInfo);
                }
            }
        }

        if (message.getSendResult() && !results.isEmpty()) {
            listenerMulticaster.multicast(IMListenerType.PRIVATE_MESSAGE, results);
        }
    }

    // ================== 【核心方法】群聊消息 ==================
    public <T> void sendGroupMessage(IMGroupMessage<T> message) {
        Map<Long, Map<Object, Object>> allOnlineStatus = getAllOnlineStatus(message.getRecvIds());

        // 满分重构：清理掉原作者那个毫无意义的死代码（sendMap 循环）。极其清爽！
        Map<Object, List<IMUserInfo>> serverMap = allOnlineStatus.entrySet().stream().flatMap(entry -> {
            Long userId = entry.getKey();
            return entry.getValue().entrySet().stream()
                    .filter(t -> message.getRecvTerminals().contains(Integer.valueOf(t.getKey().toString())))
                    .map(t -> Map.entry(t.getValue(), new IMUserInfo(userId, Integer.valueOf(t.getKey().toString()))));
        }).collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        serverMap.forEach((serverId, receivers) -> {
            IMRecvInfo recvInfo = new IMRecvInfo();
            recvInfo.setCmd(IMCmdType.GROUP_MESSAGE.code());
            recvInfo.setReceivers(receivers);
            recvInfo.setSender(message.getSender());
            recvInfo.setServiceName(appName);
            recvInfo.setSendResult(message.getSendResult());
            recvInfo.setData(message.getData());

            String key = String.join(":", IMRedisKey.IM_MESSAGE_GROUP_QUEUE, serverId.toString());
            redisMQTemplate.opsForList().rightPush(key, recvInfo);
        });

        // 自己的其他终端 (SendToSelf)
        if (message.getSendToSelf()) {
            String selfKey = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, message.getSender().getId().toString());
            for (Integer terminal : IMTerminalType.codes()) {
                if (terminal.equals(message.getSender().getTerminal())) continue;

                Integer serverId = (Integer) redisMQTemplate.opsForHash().get(selfKey, terminal.toString());
                if (serverId != null) {
                    IMRecvInfo recvInfo = new IMRecvInfo();
                    recvInfo.setCmd(IMCmdType.GROUP_MESSAGE.code());
                    recvInfo.setSender(message.getSender());
                    recvInfo.setReceivers(Collections.singletonList(new IMUserInfo(message.getSender().getId(), terminal)));
                    recvInfo.setSendResult(false);
                    recvInfo.setData(message.getData());
                    String sendKey = String.join(":", IMRedisKey.IM_MESSAGE_GROUP_QUEUE, serverId.toString());
                    redisMQTemplate.opsForList().rightPush(sendKey, recvInfo);
                }
            }
        }

        Set<Long> onlineUsersSet = allOnlineStatus.keySet();
        Set<Long> offLineUsers = message.getRecvIds().stream().filter(id -> !onlineUsersSet.contains(id)).collect(Collectors.toSet());
        if (message.getSendResult() && !offLineUsers.isEmpty()) {
            List<IMSendResult> results = offLineUsers.stream().map(userId -> {
                IMSendResult result = new IMSendResult();
                result.setSender(message.getSender());
                result.setReceiver(new IMUserInfo(userId, IMTerminalType.APP.code()));
                result.setCode(IMSendCode.NOT_ONLINE.code());
                result.setData(message.getData());
                return result;
            }).collect(Collectors.toList());
            listenerMulticaster.multicast(IMListenerType.GROUP_MESSAGE, results);
        }
    }

    // ================== 基础状态查询引擎 ==================

    /**
     * 满分重构：复用 Pipeline 引擎，极其优雅
     */
    public Map<Long, List<IMTerminalType>> getOnlineTerminal(List<Long> userIds) {
        Map<Long, Map<Object, Object>> statusMap = getAllOnlineStatus(userIds);
        Map<Long, List<IMTerminalType>> resultMap = new HashMap<>();

        statusMap.forEach((userId, terminals) -> {
            List<IMTerminalType> types = terminals.keySet().stream()
                    .map(t -> IMTerminalType.fromCode(Integer.parseInt(t.toString())))
                    .collect(Collectors.toList());
            resultMap.put(userId, types);
        });
        return resultMap;
    }

    /**
     * 判断某个具体终端是否在线 (Hash 结构 O(1) 查询)
     */
    public Boolean isOnline(Long userId, IMTerminalType terminal) {
        String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, userId.toString());
        return redisMQTemplate.opsForHash().hasKey(key, terminal.code().toString());
    }

    /**
     * 解决 Redis 史上最大雪崩：彻底干掉 keys * ！！
     * 只要 Hash 里有东西，证明有终端在线
     */
    public Boolean isOnline(Long userId) {
        String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, userId.toString());
        return redisMQTemplate.hasKey(key);
    }

    public List<Long> getOnlineUser(List<Long> userIds) {
        return new LinkedList<>(getOnlineTerminal(userIds).keySet());
    }

    public Map<Long, Map<Object, Object>> getAllOnlineStatus(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) return Collections.emptyMap();

        List<Object> results = redisMQTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long userId : userIds) {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_SESSION, userId.toString());
                connection.hGetAll(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        Map<Long, Map<Object, Object>> onlineStatusMap = new HashMap<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            Object res = results.get(i);
            if (res instanceof Map && !((Map<?, ?>) res).isEmpty()) {
                onlineStatusMap.put(userIds.get(i), (Map<Object, Object>) res);
            }
        }
        return onlineStatusMap;
    }
}