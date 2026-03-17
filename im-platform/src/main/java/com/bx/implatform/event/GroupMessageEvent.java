package com.bx.implatform.event;

import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.entity.PrivateMessage;
import org.springframework.context.ApplicationEvent;

public class GroupMessageEvent extends ApplicationEvent {

    private final GroupMessage groupMessage;
    public GroupMessageEvent(Object source, GroupMessage message) {
        super(source);
        this.groupMessage=message;
    }

    public GroupMessage getMessage() {
        return groupMessage;
    }
}
