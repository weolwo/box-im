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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IMSender {

    @Autowired
    private RedisMQTemplate redisMQTemplate;

    @Value("${spring.application.name}")
    private String appName;

    private final MessageListenerMulticaster listenerMulticaster;


    public<T> void sendSystemMessage(IMSystemMessage<T> message){
        // 根据群聊每个成员所连的IM-server，进行分组
        Map<String, IMUserInfo> sendMap = new HashMap<>();
        for (Integer terminal : message.getRecvTerminals()) {
            message.getRecvIds().forEach(id -> {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, id.toString(), terminal.toString());
                sendMap.put(key,new IMUserInfo(id, terminal));
            });
        }
        // 批量拉取
        List<Object> serverIds = redisMQTemplate.opsForValue().multiGet(sendMap.keySet());
        // 格式:map<服务器id,list<接收方>>
        Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>();
        List<IMUserInfo> offLineUsers = new LinkedList<>();
        int idx = 0;
        for (Map.Entry<String,IMUserInfo> entry : sendMap.entrySet()) {
            Integer serverId = (Integer)serverIds.get(idx++);
            if (!Objects.isNull(serverId)) {
                List<IMUserInfo> list = serverMap.computeIfAbsent(serverId, o -> new LinkedList<>());
                list.add(entry.getValue());
            } else {
                // 加入离线列表
                offLineUsers.add(entry.getValue());
            }
        }
        // 逐个server发送
        for (Map.Entry<Integer, List<IMUserInfo>> entry : serverMap.entrySet()) {
            IMRecvInfo recvInfo = new IMRecvInfo();
            recvInfo.setCmd(IMCmdType.SYSTEM_MESSAGE.code());
            recvInfo.setReceivers(new LinkedList<>(entry.getValue()));
            recvInfo.setServiceName(appName);
            recvInfo.setSendResult(message.getSendResult());
            recvInfo.setData(message.getData());
            // 推送至队列
            String key = String.join(":", IMRedisKey.IM_MESSAGE_SYSTEM_QUEUE, entry.getKey().toString());
            redisMQTemplate.opsForList().rightPush(key, recvInfo);
        }
        // 对离线用户回复消息状态
        if(message.getSendResult() && !offLineUsers.isEmpty()){
            List<IMSendResult> results = new LinkedList<>();
            for (IMUserInfo offLineUser : offLineUsers) {
                IMSendResult result = new IMSendResult();
                result.setReceiver(offLineUser);
                result.setCode(IMSendCode.NOT_ONLINE.code());
                result.setData(message.getData());
                results.add(result);
            }
            listenerMulticaster.multicast(IMListenerType.SYSTEM_MESSAGE, results);
        }
    }


    public<T> void sendPrivateMessage(IMPrivateMessage<T> message) {
        List<IMSendResult> results = new LinkedList<>();
        if(!Objects.isNull(message.getRecvId())){
            for (Integer terminal : message.getRecvTerminals()) {
                // 获取对方连接的channelId
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, message.getRecvId().toString(), terminal.toString());
                Integer serverId = (Integer)redisMQTemplate.opsForValue().get(key);
                // 如果对方在线，将数据存储至redis，等待拉取推送
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

        // 推送给自己的其他终端
        if(message.getSendToSelf()){
            for (Integer terminal : IMTerminalType.codes()) {
                if (message.getSender().getTerminal().equals(terminal)) {
                    continue;
                }
                // 获取终端连接的channelId
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, message.getSender().getId().toString(), terminal.toString());
                Integer serverId = (Integer)redisMQTemplate.opsForValue().get(key);
                // 如果终端在线，将数据存储至redis，等待拉取推送
                if (serverId != null) {
                    String sendKey = String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString());
                    IMRecvInfo recvInfo = new IMRecvInfo();
                    // 自己的消息不需要回推消息结果
                    recvInfo.setSendResult(false);
                    recvInfo.setCmd(IMCmdType.PRIVATE_MESSAGE.code());
                    recvInfo.setSender(message.getSender());
                    recvInfo.setReceivers(Collections.singletonList(new IMUserInfo(message.getSender().getId(), terminal)));
                    recvInfo.setData(message.getData());
                    redisMQTemplate.opsForList().rightPush(sendKey, recvInfo);
                }
            }
        }
        // 对离线用户回复消息状态
        if(message.getSendResult() && !results.isEmpty()){
            listenerMulticaster.multicast(IMListenerType.PRIVATE_MESSAGE, results);
        }

    }

    public<T> void sendGroupMessage(IMGroupMessage<T> message) {

        // 1. 获取所有接收者的在线状态 (Map<userId, Map<terminal, serverId>>)
        // 这里我们可以写个批量工具，一次性拿到所有人的 Hash 数据
        Map<Long, Map<Object, Object>> allOnlineStatus = getAllOnlineStatus(message.getRecvIds());
        //最终转换成 安卓服务器id分组 Map<serverId, List<IMUserInfo>>
        Map<Object, List<IMUserInfo>> serverMap = allOnlineStatus.entrySet().stream().flatMap(entry -> {
            Long userId = entry.getKey();
            Map<Object, Object> terminals = entry.getValue();
            return terminals.entrySet().stream()
                    // 过滤掉不在本次发送范围内的终端
                    .filter(t -> message.getRecvTerminals().contains(t.getKey()))
                    // 转换为 Entry<serverId, IMUserInfo>
                    .map(t -> Map.entry(t.getValue(), new IMUserInfo(userId, Integer.valueOf(t.getKey().toString()))));
        }).collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        // 根据群聊每个成员所连的IM-server，进行分组
        Map<String, IMUserInfo> sendMap = new HashMap<>();
        for (Integer terminal : message.getRecvTerminals()) {
            message.getRecvIds().forEach(id -> {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, id.toString(), terminal.toString());
                sendMap.put(key,new IMUserInfo(id, terminal));
            });
        }
        // 逐个server发送
        serverMap.forEach((serverId, receivers) -> {
            IMRecvInfo recvInfo = new IMRecvInfo();
            recvInfo.setCmd(IMCmdType.GROUP_MESSAGE.code());
            recvInfo.setReceivers(receivers);
            recvInfo.setSender(message.getSender());
            recvInfo.setServiceName(appName);
            recvInfo.setSendResult(message.getSendResult());
            recvInfo.setData(message.getData());
            // 推送至队列
            String key = String.join(":", IMRedisKey.IM_MESSAGE_GROUP_QUEUE, serverId.toString());
            redisMQTemplate.opsForList().rightPush(key, recvInfo);
        });


        // 推送给自己的其他终端
        if (message.getSendToSelf()) {
            for (Integer terminal : IMTerminalType.codes()) {
                if (terminal.equals(message.getSender().getTerminal())) {
                    continue;
                }
                // 获取终端连接的channelId
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, message.getSender().getId().toString(), terminal.toString());
                Integer serverId = (Integer)redisMQTemplate.opsForValue().get(key);
                // 如果终端在线，将数据存储至redis，等待拉取推送
                if (!Objects.isNull(serverId)) {
                    IMRecvInfo recvInfo = new IMRecvInfo();
                    recvInfo.setCmd(IMCmdType.GROUP_MESSAGE.code());
                    recvInfo.setSender(message.getSender());
                    recvInfo.setReceivers(Collections.singletonList(new IMUserInfo(message.getSender().getId(), terminal)));
                    // 自己的消息不需要回推消息结果
                    recvInfo.setSendResult(false);
                    recvInfo.setData(message.getData());
                    String sendKey = String.join(":", IMRedisKey.IM_MESSAGE_GROUP_QUEUE, serverId.toString());
                    redisMQTemplate.opsForList().rightPush(sendKey, recvInfo);
                }
            }
        }
        //所有接收者 ID 减去 在线用户 ID，剩下的就是离线用户。
        Set<Long> onlineUsersSet = allOnlineStatus.keySet();
        Set<Long> offLineUsers = message.getRecvIds().stream().filter(id -> !onlineUsersSet.contains(id)).collect(Collectors.toSet());
        // 对离线用户回复消息状态
        if(message.getSendResult() && !offLineUsers.isEmpty()){
            List<IMSendResult> results = offLineUsers.stream().map(userId -> {
                IMSendResult result = new IMSendResult();
                result.setSender(message.getSender());
                result.setReceiver(new IMUserInfo(userId, IMTerminalType.APP.code())); // 默认指向主终端
                result.setCode(IMSendCode.NOT_ONLINE.code());
                result.setData(message.getData());
                return result;
            }).collect(Collectors.toList());
            // 广播离线状态，前端可以据此展示“未读”或“送达失败”
            listenerMulticaster.multicast(IMListenerType.GROUP_MESSAGE, results);
        }

    }

    public Map<Long,List<IMTerminalType>> getOnlineTerminal(List<Long> userIds){
        if(CollUtil.isEmpty(userIds)){
            return Collections.emptyMap();
        }
        // 把所有用户的key都存起来
        Map<String,IMUserInfo> userMap = new HashMap<>();
        for(Long id:userIds){
            for (Integer terminal : IMTerminalType.codes()) {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, id.toString(), terminal.toString());
                userMap.put(key,new IMUserInfo(id,terminal));
            }
        }
        // 批量拉取
        List<Object> serverIds = redisMQTemplate.opsForValue().multiGet(userMap.keySet());
        int idx = 0;
        Map<Long,List<IMTerminalType>> onlineMap = new HashMap<>();
        for (Map.Entry<String,IMUserInfo> entry : userMap.entrySet()) {
            // serverid有值表示用户在线
            if(serverIds.get(idx++) != null){
                IMUserInfo userInfo = entry.getValue();
                List<IMTerminalType> terminals = onlineMap.computeIfAbsent(userInfo.getId(), o -> new LinkedList<>());
                terminals.add(IMTerminalType.fromCode(userInfo.getTerminal()));
            }
        }
        // 去重并返回
        return onlineMap;
    }

    public Boolean isOnline(Long userId, IMTerminalType terminal) {
        String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, userId.toString(), terminal.code().toString());
        return redisMQTemplate.hasKey(key);
    }

    public Boolean isOnline(Long userId) {
        String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, userId.toString(), "*");
        return !Objects.requireNonNull(redisMQTemplate.keys(key)).isEmpty();
    }

    public List<Long> getOnlineUser(List<Long> userIds){
        return new LinkedList<>(getOnlineTerminal(userIds).keySet());
    }

    /**
     * 批量获取用户的在线状态（基于 Hash 结构）
     * 返回格式: Map<用户ID, Map<终端类型, 服务器ID>>
     */
    public Map<Long, Map<Object, Object>> getAllOnlineStatus(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }

        // 1. 使用 Redis Pipeline 批量执行，减少网络 IO 开销
        List<Object> results = redisMQTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long userId : userIds) {
                String key = String.join(":", IMRedisKey.IM_USER_SERVER_ID, userId.toString());
                // 执行 HGETALL 命令获取该用户下所有 field(terminal) 和 value(serverId)
                connection.hGetAll(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        // 2. 将结果与 userId 重新装配
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
