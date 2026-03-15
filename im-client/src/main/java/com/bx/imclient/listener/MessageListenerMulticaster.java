package com.bx.imclient.listener;


import cn.hutool.core.collection.CollUtil;
import com.bx.imclient.annotation.IMListener;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.model.IMSendResult;
import com.bx.imcommon.util.JsonUtils;
import com.fasterxml.jackson.databind.JavaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

@Component
public class MessageListenerMulticaster {

    @Autowired(required = false)
    private List<MessageListener>  messageListeners  = Collections.emptyList();

    public  void multicast(IMListenerType listenerType, List<IMSendResult> results){
        if(CollUtil.isEmpty(results)){
            return;
        }
        for(MessageListener listener:messageListeners){
            IMListener annotation = listener.getClass().getAnnotation(IMListener.class);
            if(annotation!=null && (annotation.type().equals(IMListenerType.ALL) || annotation.type().equals(listenerType))){
                results.forEach(result->{
                    // 将data转回对象类型
                    if (result.getData() != null) {
                        // 这两行反射获取泛型 Type 的代码不用动，这是 Java 原生的反射机制
                        Type superClass = listener.getClass().getGenericInterfaces()[0];
                        Type type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
                        // 2. 魔法时刻：把 Java 原生的 Type，转换成 Jackson 认识的 JavaType
                        JavaType javaType = JsonUtils.getMapper().getTypeFactory().constructType(type);
                        // 3. 终极转换：使用 Jackson 的 convertValue 完美平替 Fastjson 的 toJavaObject
                        Object convertedData = JsonUtils.getMapper().convertValue(result.getData(), javaType);
                        // 4. 重新塞回去
                        result.setData(convertedData);
                    }
                });
                // 回调到调用方处理
                listener.process(results);
            }
        }
    }


}
