package com.qiniu.tutorial;

import com.qiniu.pandora.common.PandoraClient;
import com.qiniu.pandora.common.PandoraClientImpl;
import com.qiniu.pandora.common.QiniuException;
import com.qiniu.pandora.logdb.LogDBClient;
import com.qiniu.pandora.pipeline.PipelineClient;
import com.qiniu.pandora.util.Auth;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Created by jemy on 2018/7/6.
 */
public class Clear {
    private PipelineClient pipelineClient;
    private LogDBClient logDBClient;

    private String pipelineHost;
    private String logdbHost;
    private String accessKey;
    private String secretKey;
    private String workflowName;
    private String pipelineRepo;
    private String logdbRepo;
    private String exportName;

    @Before
    public void tearUp() throws IOException {
        File cfgFile = LoggerContext.getContext().getRootLogger().getContext().getConfiguration().
                getConfigurationSource().getFile();
        FileInputStream fs = new FileInputStream(cfgFile);
        Properties props = new Properties();
        props.load(fs);
        fs.close();

        this.pipelineHost = props.getProperty("appender.qiniu.pipelineHost");
        this.logdbHost = props.getProperty("appender.qiniu.logdbHost");
        this.accessKey = props.getProperty("appender.qiniu.accessKey");
        this.secretKey = props.getProperty("appender.qiniu.secretKey");
        this.workflowName = props.getProperty("appender.qiniu.workflowName");
        this.pipelineRepo = props.getProperty("appender.qiniu.pipelineRepo");
        this.logdbRepo = props.getProperty("appender.qiniu.logdbRepo");
        this.exportName = String.format("%s_export_to_%s", this.pipelineRepo, this.logdbRepo);

        Auth auth = Auth.create(this.accessKey, this.secretKey);
        PandoraClient client = new PandoraClientImpl(auth);
        this.pipelineClient = new PipelineClient(client, this.pipelineHost);
        this.logDBClient = new LogDBClient(client, this.logdbHost);
    }

    @Test
    public void testResetWorkflow() {
        //delete export
        try {
            this.pipelineClient.deleteExport(this.pipelineRepo, this.exportName);
        } catch (QiniuException e) {
            e.printStackTrace();
        }

        //delete old logdb
        try {
            this.logDBClient.deleteRepo(this.logdbRepo);
        } catch (QiniuException e) {
            e.printStackTrace();
        }

        //delete pipeline
        try {
            this.pipelineClient.deleteRepo(this.pipelineRepo);
        } catch (QiniuException e) {
            e.printStackTrace();
        }

        //delete old workflow
        try {
            this.pipelineClient.deleteWorkflow(this.workflowName);
        } catch (QiniuException e) {
            e.printStackTrace();
        }
    }
}
