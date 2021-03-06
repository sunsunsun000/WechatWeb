package me.hupeng.web.wechat.module.v1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.hupeng.web.wechat.MainSetup;
import me.hupeng.web.wechat.bean.Message;
import me.hupeng.web.wechat.bean.User;
import me.hupeng.web.wechat.util.Toolkit;
import org.nutz.dao.Cnd;
import org.nutz.dao.Dao;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.log.Log;
import org.nutz.log.Logs;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value = "/v1/chat")
@IocBean(singleton = true)
public class MessageWebSocket {
    Dao dao = MainSetup.dao;
    Gson gson =  new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
    Log log = Logs.get();

    /**
     * 存放Websocket Session Id --> Session 的映射关系
     */
    protected static ConcurrentHashMap<String, Session> sessionId2Session = new ConcurrentHashMap<>();

    /**
     * 用户Id --> SessionId的映射关系
     */
    protected static ConcurrentHashMap<Object, String> userId2SessionId = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        sessionId2Session.put(session.getId(), session);
        System.out.println("会话：" + session.getId() + " 连入服务器");

    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason){
        sessionId2Session.remove(session.getId());
        System.out.println("会话："+ session.getId() + " 离开服务器");
    }

    /**
     * WebSocket会话出错时调用,默认调用onClose.
     */
    public void onError(Session session, Throwable throwable) {
        System.out.println("会话："+ session.getId() + " 发生错误");
        onClose(session, null);
    }

    @OnMessage
    public void onMessage(String message, Session session) throws IOException,
            InterruptedException {
        //调用消息处理函数
        processMessage(message, session);
    }

    /**
     * 处理用户消息
     * */
    private void processMessage(String msgStr, Session session){
        try {
            Msg msg =  gson.fromJson(msgStr, Msg.class);
            switch (msg.getOperate()){
                case Msg.HEART_BEAT:
                    System.out.println("收到心跳包，来自用户:" + msg.getFrom());
                    break;
                case Msg.ON_LINE:
                    onLine(msg.from, session);
                    break;
                case Msg.SEND_MESSAGE:
                    String fromKey = dao.fetch(User.class,Cnd.where("id","=",msg.from)).getAk().substring(0,24);
                    msg.setMessage(Toolkit._3DES_decode(fromKey.getBytes(),Toolkit.hexstr2bytearray(msg.getMessage())));
                    sendMessage(msg.from, msg.to, msg.message);
                    break;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 用户上线
     * */
    private void onLine(int userId, Session session){
        Log log= Logs.get();
        log.info("用户" + userId + "上线了");

        //查看此用户之前是否存在登录状态,有则清除
        try {
            String sessionId = userId2SessionId.get(userId);
            sessionId2Session.remove(sessionId);
            userId2SessionId.remove(userId);
        }catch (Exception e){

        }
        //添加session与用户之间的关联
        try {
            userId2SessionId.put(userId, session.getId());
        }catch (Exception e){

        }

        //检查用户名下是否有未收到的消息,有则发送之
        List<Message> list = dao.query(Message.class, Cnd.where("to_user", "=", userId).and("send_result","=",0).asc("id"));
        for(Message message : list){
            try {
                Msg msg = new Msg();
                msg.from = message.getFromUser();
                msg.to = message.getToUser();
                msg.sendTime = message.getSendTime();
                msg.message = message.getMessage();

                //加密消息后发送
                String toKey = dao.fetch(User.class,Cnd.where("id","=",msg.to)).getAk().substring(0,24);
                msg.setMessage(Toolkit._3DES_encode(toKey.getBytes(),msg.getMessage().getBytes()));
                session.getAsyncRemote().sendText(gson.toJson(msg));
                message.setSendResult(1);
                dao.update(message);
            }catch (Exception e){

            }
        }
    }

    /**
     * 用户发送消息
     * */
    private void sendMessage(int from, int to, String msgStr){
        Log log= Logs.get();


        log.info("用户" + from + "给用户" +  to + "发送了一条消息,消息内容为:" + msgStr);
        //存放数据库
        Message message= new Message();
        message.setSendTime(new Date(System.currentTimeMillis()));
        message.setFromUser(from);
        message.setToUser(to);
        message.setMessage(msgStr);
        message.setSendResult(0);
        dao.insert(message);

        //判断有没有session
        ////有session且发送成功则修改消息状态
        String sessionId = userId2SessionId.get(to);
        if (sessionId != null){
            Session session = sessionId2Session.get(sessionId);
            try {
                if (session != null){
                    Msg msg = new Msg();
                    msg.from = from;
                    msg.to = to;
                    msg.message = msgStr;
                    msg.sendTime = new Date(System.currentTimeMillis());
                    //加密消息后发送
                    String toKey = dao.fetch(User.class,Cnd.where("id","=",msg.to)).getAk().substring(0,24);
                    msg.setMessage(Toolkit._3DES_encode(toKey.getBytes(),msg.getMessage().getBytes()));

                    session.getAsyncRemote().sendText(gson.toJson(msg));
                    message.setSendResult(1);
                    dao.update(message);
                }
            }catch (Exception e){

            }
        }
    }


    /**
     * 消息类
     */
    public static class Msg {
        /**
         * 心跳包
         * */
        public final static int HEART_BEAT = 0x00;

        /**
         * 上线
         * */
        public final static int ON_LINE = 0x01;
        /**
         * 发送消息
         * */
        public final static int SEND_MESSAGE = 0x02;


        /**
         *  操作
         * */
        private int operate;

        /**
         * 发信方
         * */
        private int from;

        /**
         * 收信方
         * */
        private int to;

        /**
         * 消息内容
         * */
        private String message;

        /**
         * 发送时间
         * */
        private Date sendTime;

        private String accessKey;

        public int getOperate() {
            return operate;
        }

        public void setOperate(int operate) {
            this.operate = operate;
        }

        public int getFrom() {
            return from;
        }

        public void setFrom(int from) {
            this.from = from;
        }

        public int getTo() {
            return to;
        }

        public void setTo(int to) {
            this.to = to;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public Date getSendTime() {
            return sendTime;
        }

        public void setSendTime(Date sendTime) {
            this.sendTime = sendTime;
        }
    }
}
