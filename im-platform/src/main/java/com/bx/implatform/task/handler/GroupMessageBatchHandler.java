package com.bx.implatform.task.handler;

import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.event.GroupMessageEvent;
import com.bx.implatform.event.PrivateMessageEvent;
import com.bx.implatform.mapper.GroupMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Order(100) // 数字越小，越先执行！比如先启动 Netty (@Order(10))，再启动批处理引擎 (@Order(100))
public  class GroupMessageBatchHandler extends AbstractBatchHandler<GroupMessage> {

    private final GroupMessageMapper groupMessageMapper; // 或者 MyBatis Plus 的 IService


    /**
     * ⚡️ 监听器：听到广播后，什么都不干，只是把消息塞进内存队列
     * 注意：这里非常快，耗时不到 0.001 毫秒
     */
    @EventListener
    public void onMessageArrived(GroupMessageEvent event) {
        this.submit(event.getMessage());
    }

    @Override
    public void flushToDb(List messages) {
        groupMessageMapper.insertBatch(messages);
    }
}
