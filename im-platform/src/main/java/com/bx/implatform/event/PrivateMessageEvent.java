package com.bx.implatform.event;

import com.bx.implatform.entity.PrivateMessage;
import org.springframework.context.ApplicationEvent;
/**
 * 领域事件：收到新的私聊消息
 */
public class PrivateMessageEvent extends ApplicationEvent {

    private final PrivateMessage message;
    public PrivateMessageEvent(Object source,PrivateMessage message) {
        super(source);
        this.message=message;
    }

    public PrivateMessage getMessage() {
        return message;
    }
}
