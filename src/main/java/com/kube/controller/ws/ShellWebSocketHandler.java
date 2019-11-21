package com.kube.controller.ws;

import com.kube.dto.TerminalWsConnection;
import com.kube.util.URIQueryUtil;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.Exec;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class ShellWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShellWebSocketHandler.class);

    private static final int corePoolSize = 5;
    private static final int maximumPoolSize = 50;
    private static final ConcurrentHashMap<String, TerminalWsConnection> TerminalWsConnections = new ConcurrentHashMap<>();
    /*@Autowired
    private IAuthenticationService authenticationService;
    @Autowired
    private EnvironmentClient environmentClient;*/

    private static final ExecutorService terminalThreadPoolExecutor = new ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            5000,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(5));

    public ShellWebSocketHandler() {
    }

    /**
     * 建立连接
     *
     * @param session
     * @throws Exception
     */
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {

        Map<String, String> requestParams = URIQueryUtil.splitQuery(session.getUri());

        /*String token = requestParams.get(CommonConstants.AUTHORIZATION);
        String msg = this.authenticationService.authenticate(token);
        if (msg != null) {
            try {
                session.sendMessage(new TextMessage(msg.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                LOGGER.warn(e.getMessage());
            }
            return;
        }*/
        if (TerminalWsConnections.size() < maximumPoolSize) {
            if (!TerminalWsConnections.containsKey(session.getId())) {

                /*String envIdStr = requestParams.get("envId");
                if (StringUtils.isBlank(envIdStr)) {
                    throw new BusinessException(TipsConstants.ARGUMENT_FAILURE, "request parameter envId cannot be null.");
                }
                long envId = Long.parseLong(envIdStr);

                SingleResponse<ConfigDto> configResp = environmentClient.findKubernetesConfigDtoByEnvId(envId);
                if (!configResp.isSuccess()) {
                    throw new BusinessException(configResp.getErrorCode(), configResp.getMsg());
                }
                ConfigDto configDto = configResp.getData();
                ApiClient apiClient = new ClientBuilder().setBasePath(configDto.getUrl())
                        .setVerifyingSsl(false)
                        .setAuthentication(new AccessTokenAuthentication(configDto.getToken()))
                        .build();*/

                ApiClient apiClient = new ClientBuilder()
                        .setBasePath("https://127.0.0.1:6443")
                        .setVerifyingSsl(false)
                        .setAuthentication(new AccessTokenAuthentication("token"))
                        .build();

                apiClient.getHttpClient().setConnectTimeout(360, TimeUnit.SECONDS);
                apiClient.getHttpClient().setReadTimeout(360, TimeUnit.SECONDS);
                apiClient.getHttpClient().setWriteTimeout(360, TimeUnit.SECONDS);

                Exec exec = new Exec(apiClient);

                TerminalWsConnection terminalWsConnection = new TerminalWsConnection(requestParams, session, exec);
                TerminalWsConnections.putIfAbsent(session.getId(), terminalWsConnection);
                terminalThreadPoolExecutor.submit(terminalWsConnection);
                LOGGER.info("TerminalWsConnection connected, session id: {}, connecting count: {}",
                        session.getId(), TerminalWsConnections.size());
            }
        } else {
            LOGGER.warn("terminal thread pool is full");
            TextMessage textMessage = new TextMessage("terminal thread pool is full".getBytes(StandardCharsets.UTF_8));
            session.sendMessage(textMessage);
        }

    }

    /**
     * 关闭连接时触发
     *
     * @param session
     * @param closeStatus
     * @throws Exception
     */
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        if (TerminalWsConnections.containsKey(session.getId())) {
            TerminalWsConnections.get(session.getId()).exit();
            TerminalWsConnections.remove(session.getId());
            LOGGER.info("TerminalWsConnection disconnected, sessionId: {}, connecting count: {}",
                    session.getId(), TerminalWsConnections.size());
        }

    }

    /**
     * 处理消息
     *
     * @param session
     * @param message
     * @throws Exception
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (TerminalWsConnections.containsKey(session.getId())) {
            Process proc = TerminalWsConnections.get(session.getId()).getProc();
            if (proc != null) {
                if (session.isOpen()) {
                    proc.getOutputStream()
                            .write(message.getPayload().getBytes(StandardCharsets.UTF_8));
                }
            }
            super.handleTextMessage(session, message);
        }
    }

    /**
     * 处理传输错误
     *
     * @param session
     * @param exception
     * @throws Exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close();
        }
        if (TerminalWsConnections.containsKey(session.getId())) {
            TerminalWsConnections.get(session.getId()).exit();
            TerminalWsConnections.remove(session.getId());
            LOGGER.info("TerminalWsConnection disconnected because of error: {}, connecting count: {}",
                    exception.getMessage(), TerminalWsConnections.size());
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

}