package com.kube.dto;

import com.kube.controller.ws.ShellWebSocketHandler;
import io.kubernetes.client.Exec;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TerminalWsConnection extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShellWebSocketHandler.class);
    public Process proc;
    private Exec exec;
    private WebSocketSession webSocketSession;
    private Pod pod;
    private ConsoleSize consoleSize;

    public TerminalWsConnection(Map<String, String> requestParams, WebSocketSession session, Exec exec) {
        this.setWebSocketSession(session);
        this.setExec(exec);
        String namespace = requestParams.get("namespace");
        if (StringUtils.isBlank(namespace)) {
            namespace = "default";
        }
        this.setPod(new Pod(requestParams.get("name"), namespace, requestParams.get("container")));
        this.setConsoleSize(new ConsoleSize(requestParams.get("cols"), requestParams.get("rows")));
    }

    /**
     * 线程任务
     */
    @Override
    public void run() {
        List<String> commands = Arrays.asList("/bin/bash", "/bin/sh");
        for (String command : commands) {
            boolean shellResult = this.startProcess(command);
            if (shellResult) {
                break;
            }
        }
        LOGGER.info("session closed. exit thread");
        // SpringWebSocketHandler.TerminalWsConnections.remove(this.webSocketSession.getId());
    }

    /**
     * 连接 k8s 在指定 pod 容器中执行命令
     *
     * @param command
     */
    private boolean startProcess(String command) {
        LOGGER.info("k8s exec {}", command);
        String namespace = this.getPod().getNamespace();
        String name = this.getPod().getName();
        String container = this.getPod().getContainer();
        boolean shellResult = false;
        try {
            proc = this.exec.exec(namespace, name, new String[]{command}, container, true, true);
        } catch (Exception e) {
            String msg = "k8s client exec cmd error: " + e.getMessage();
            LOGGER.error(msg);
            TextMessage textMessage = new TextMessage(msg.getBytes(StandardCharsets.UTF_8));
            try {
                this.webSocketSession.sendMessage(textMessage);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return true;
        }

        try {
            while (true) {
                byte data[] = new byte[1024];
                if (this.proc.getInputStream().read(data) != -1) {
                    TextMessage textMessage = new TextMessage(data);
                    if (isInValidBash(textMessage, command)) {
                        this.webSocketSession.sendMessage(textMessage);
                        break;
                    } else {
                        shellResult = true;
                    }
                    this.webSocketSession.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("inputStream Pipe has closed.");
        } finally {
            this.exit();
        }
        return shellResult;
    }

    /**
     * 验证命令执行结果
     *
     * @param textMessage
     * @param command
     * @return
     */
    private boolean isInValidBash(TextMessage textMessage, String command) {
        String failMessage = "exec failed";
        if (textMessage.getPayload().trim().contains(failMessage)) {
            LOGGER.warn("oci runtime error: exec failed: {}", command);
            return true;
        } else {
            return false;
        }
    }

    /**
     * 最后关闭数据流
     */
    protected void finalize() {
        if (this.proc != null) {
            this.proc.destroyForcibly();
        }
    }

    /**
     * 退出 Process
     */
    public void exit() {
        if (this.proc != null) {
            this.proc.destroyForcibly();
        }
    }

    public Exec getExec() {
        return exec;
    }

    public void setExec(Exec exec) {
        this.exec = exec;
    }

    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }

    public void setWebSocketSession(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    public Pod getPod() {
        return pod;
    }

    public void setPod(Pod pod) {
        this.pod = pod;
    }

    public ConsoleSize getConsoleSize() {
        return consoleSize;
    }

    public void setConsoleSize(ConsoleSize consoleSize) {
        this.consoleSize = consoleSize;
    }

    public Process getProc() {
        return proc;
    }

}