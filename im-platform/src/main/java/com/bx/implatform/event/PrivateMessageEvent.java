package com.bx.implatform.event;

import com.bx.implatform.entity.PrivateMessage;
import org.springframework.context.ApplicationEvent;

public class PrivateMessageEvent extends ApplicationEvent {

    private final PrivateMessage privateMessage;
    public PrivateMessageEvent(Object source, PrivateMessage message) {
        super(source);
        this.privateMessage=message;
    }

    public PrivateMessage getMessage() {
        return privateMessage;
    }
}
