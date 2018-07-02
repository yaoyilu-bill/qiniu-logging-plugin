package com.qiniu.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.qiniu.pandora.common.Constants;
import com.qiniu.pandora.common.PandoraClientImpl;
import com.qiniu.pandora.common.QiniuException;
import com.qiniu.pandora.http.Response;
import com.qiniu.pandora.pipeline.points.Batch;
import com.qiniu.pandora.pipeline.points.Point;
import com.qiniu.pandora.pipeline.sender.DataSender;
import com.qiniu.pandora.util.Auth;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by jemy on 2018/6/28.
 */
public class LogbackQiniuAppender extends AppenderBase<ILoggingEvent> implements Configs {
    /**
     * define workflow
     */
    private Lock rwLock;
    private Batch batch;
    private ExecutorService executorService;
    private DataSender logSender;
    private PandoraClientImpl client;
    private QiniuLoggingGuard guard;

    /*
    * define logback appender properties
    * */
    private String pipelineHost;
    private String logdbHost;
    private String workflowName;
    private String workflowRegion;
    private String pipelineRepo;
    private String logdbRepo;
    private String logdbRetention;
    private String accessKey;
    private String secretKey;
    private int autoFlushInterval;


    // error handling
    private String logCacheDir;
    private int logRotateInterval; //in seconds
    private int logRetryInterval; //in seconds

    public String getPipelineHost() {
        return pipelineHost;
    }

    public void setPipelineHost(String pipelineHost) {
        this.pipelineHost = pipelineHost;
    }

    public String getLogdbHost() {
        return logdbHost;
    }

    public void setLogdbHost(String logdbHost) {
        this.logdbHost = logdbHost;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getWorkflowRegion() {
        return workflowRegion;
    }

    public void setWorkflowRegion(String workflowRegion) {
        this.workflowRegion = workflowRegion;
    }

    public String getPipelineRepo() {
        return pipelineRepo;
    }

    public void setPipelineRepo(String pipelineRepo) {
        this.pipelineRepo = pipelineRepo;
    }

    public String getLogdbRepo() {
        return logdbRepo;
    }

    public void setLogdbRepo(String logdbRepo) {
        this.logdbRepo = logdbRepo;
    }

    public String getLogdbRetention() {
        return logdbRetention;
    }

    public void setLogdbRetention(String logdbRetention) {
        this.logdbRetention = logdbRetention;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getAutoFlushInterval() {
        return autoFlushInterval;
    }

    public void setAutoFlushInterval(int autoFlushInterval) {
        this.autoFlushInterval = autoFlushInterval;
    }

    public String getLogCacheDir() {
        return logCacheDir;
    }

    public void setLogCacheDir(String logCacheDir) {
        this.logCacheDir = logCacheDir;
    }

    public int getLogRotateInterval() {
        return logRotateInterval;
    }

    public void setLogRotateInterval(int logRotateInterval) {
        this.logRotateInterval = logRotateInterval;
    }

    public int getLogRetryInterval() {
        return logRetryInterval;
    }

    public void setLogRetryInterval(int logRetryInterval) {
        this.logRetryInterval = logRetryInterval;
    }

    @Override
    public void start() {
        super.start();
        this.batch = new Batch();
        this.executorService = Executors.newCachedThreadPool();
        Auth auth = Auth.create(this.accessKey, this.secretKey);
        this.client = new PandoraClientImpl(auth);

        if (pipelineHost != null && !pipelineHost.isEmpty()) {
            this.logSender = new DataSender(pipelineRepo, client, pipelineHost);
        } else {
            this.logSender = new DataSender(pipelineRepo, client);
        }

        //init log guard
        this.guard = QiniuLoggingGuard.getInstance();
        if (this.logCacheDir != null && !this.logCacheDir.isEmpty()) {
            try {
                this.guard.setLogCacheDir(this.logCacheDir);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        if (this.logRotateInterval > 0) {
            this.guard.setLogRotateInterval(this.logRotateInterval);
        }
        if (this.logRetryInterval > 0) {
            this.guard.setLogRetryInterval(this.logRetryInterval);
        }
        this.guard.setDataSender(this.logSender);

        //check attributes
        if (workflowRegion == null || workflowRegion.isEmpty()) {
            workflowRegion = DefaultWorkflowRegion;
        }
        if (logdbRetention == null || logdbRetention.isEmpty()) {
            logdbRetention = DefaultLogdbRetention;
        }

        //try to create appender workflow
        try {
            QiniuAppenderClient.createAppenderWorkflow(client, pipelineHost, logdbHost, workflowName, workflowRegion,
                    pipelineRepo, logdbRepo, logdbRetention);
        } catch (Exception e) {
            e.printStackTrace();
            //@TODO better handle?
        }

        if (autoFlushInterval <= 0) {
            autoFlushInterval = DefaultAutoFlushInterval;
        }
        this.rwLock = new ReentrantLock(true);
        final int autoFlushSeconds = autoFlushInterval;
        new Thread(new Runnable() {
            public void run() {
                for (; ; ) {
                    intervalFlush();
                    try {
                        TimeUnit.SECONDS.sleep(autoFlushSeconds);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();


    }

    public void intervalFlush() {
        this.rwLock.lock();
        final byte[] postBody;

        if (batch.getSize() > 0) {
            postBody = batch.toString().getBytes(Constants.UTF_8);
            this.executorService.execute(new Runnable() {
                public void run() {
                    try {
                        Response response = logSender.send(postBody);
                        response.close();
                    } catch (QiniuException e) {
                        //e.printStackTrace();
                        guard.write(postBody);
                    }
                }
            });
            batch.clear();
        }

        this.rwLock.unlock();
    }

    protected void append(ILoggingEvent logEvent) {
        Point point = new Point();
        point.append("timestamp", logEvent.getTimeStamp());
        point.append("level", logEvent.getLevel().toString());
        point.append("logger", logEvent.getLoggerName());

        if (logEvent.getMarker() != null) {
            point.append("marker", logEvent.getMarker().toString());
        } else {
            point.append("marker", "");
        }

        point.append("message", logEvent.getMessage());
        point.append("thread_name", logEvent.getThreadName());
        point.append("thread_id", 0); // log4j 1.x doest not support thread id
        point.append("thread_priority", 0);
        if (logEvent.getCallerData() != null) {
            StringBuilder exceptionBuilder = new StringBuilder();
            for (StackTraceElement msg : logEvent.getCallerData()) {
                exceptionBuilder.append(msg.toString());
            }
            point.append("exception", exceptionBuilder.toString());
        } else {
            point.append("exception", "");
        }

        //lock
        this.rwLock.lock();
        if (!batch.canAdd(point)) {

            final byte[] postBody = batch.toString().getBytes(Constants.UTF_8);
            this.executorService.execute(new Runnable() {
                public void run() {
                    try {
                        Response response = logSender.send(postBody);
                        response.close();
                    } catch (QiniuException e) {
                        //e.printStackTrace();
                        guard.write(postBody);
                    }
                }
            });

            batch.clear();
        }
        batch.add(point);
        this.rwLock.unlock();
    }
}
