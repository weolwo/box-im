package com.bx.implatform.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;

/**
 *
 * 的核心逻辑是：把前端传过来的整个请求体（Body）读取出来，存到一个内存数组 byte[] requestBody 里。它的初衷是为了解决 “流只能读取一次” 的问题
 * （因为后面的参数防抖切面、日志切面、甚至是 Controller 里的 @RequestBody 都需要反复读这个流）。
 */
public class CacheHttpServletRequestWrapper extends HttpServletRequestWrapper {
    private byte[] requestBody;
    private final HttpServletRequest request;

    public CacheHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
        this.request = request;
    }


    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (null == this.requestBody) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(request.getInputStream(), baos);
            this.requestBody = baos.toByteArray();
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(requestBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
                //return false;  永远不结束
            }

            @Override
            public int available() throws IOException {
                return bais.available();
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }

            @Override
            public int read() {
                return bais.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }


}
