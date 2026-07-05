package com.foundeo.fuseless;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyRequestContext;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import lucee.loader.servlet.jakarta.CFMLServlet;

import com.amazonaws.services.lambda.runtime.LambdaRuntime;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.lang.StringBuilder;

import org.crac.Core;
import org.crac.Resource;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServlet;
import java.io.*;

public class StreamLambdaHandler implements RequestStreamHandler {
    private static final LambdaLogger logger = LambdaRuntime.getLogger();

    private static CFMLLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

    private static HttpServlet cfmlServlet = null;

    public static boolean ENABLE_XRAY = false;

    // CRaC priming: sends representative requests through the handler in
    // beforeCheckpoint so their JIT-compiled code paths are baked into the
    // SnapStart snapshot. Held as a strong static reference per the CRaC
    // Resource contract (Context only holds a WeakReference; an unreferenced
    // registration is garbage collected and its hooks silently never run).
    // See mnjustin/hnr1#64.
    private static final Resource CRAC_PRIMING_RESOURCE = new Resource() {
        @Override
        public void beforeCheckpoint(org.crac.Context<? extends Resource> context) {
            primeJit();
        }

        @Override
        public void afterRestore(org.crac.Context<? extends Resource> context) {
            // no-op: priming runs before the snapshot is taken, not after restore
        }
    };

    static {
        Core.getGlobalContext().register(CRAC_PRIMING_RESOURCE);
    }

    /**
     * Sends a representative request or two through the CFML handler before
     * the SnapStart snapshot is taken, so their JIT-compiled hot paths are
     * captured in the snapshot instead of being paid for on the first live
     * request after restore. Failures here must not prevent the checkpoint
     * from proceeding.
     */
    private static void primeJit() {
        if (handler == null || cfmlServlet == null) {
            log("primeJit: handler not initialized, skipping priming");
            return;
        }
        try {
            AwsProxyRequest req = new AwsProxyRequest();
            req.setHttpMethod("GET");
            req.setPath("/index.cfm");
            req.setBody("");
            req.setRequestContext(new AwsProxyRequestContext());

            long start = System.currentTimeMillis();
            handler.proxy(req, new PrimingContext());
            log("primeJit: warmup request completed in " + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable t) {
            log("primeJit: warmup request failed, continuing checkpoint anyway", t);
        }
    }

    static {
        try {
            logger.log("FuseLess: StreamLambdaHandler initializing");

            //load Log4j


            if (System.getenv("FUSELESS_ENABLE_XRAY") != null && System.getenv("FUSELESS_ENABLE_XRAY").equals("true")) {
                ENABLE_XRAY = true;
            }
            
            




            handler = CFMLLambdaContainerHandler.getAwsProxyHandler();
            

            // we use the onStartup method of the handler to register our custom filter
            handler.onStartup(servletContext -> {
                long startTime = System.currentTimeMillis();
                log("StreamLambdaHandler onStartup");
                PrintStream systemOUT = System.out;
                try {
                    //redirecting System.out to a file because lucee logs a bunch of stuff with it
                    //this is causing sam local to have issues since it uses System.out to look for the 
                    //response
                    System.setOut(new PrintStream(new OutputStream() {
                        public void write(int b) {
                        //DO NOTHING
                        }
                    }));
                } catch (Exception e) {
                    log("FuseLess: Error redirecting system.out", e);
                }

                System.setProperty("lucee.web.dir", "/tmp/lucee/web/");
                System.setProperty("lucee.base.dir", "/tmp/lucee/server/");
                String lucee_extensions_install = System.getenv("LUCEE_EXTENSIONS_INSTALL");
                if (lucee_extensions_install == null) {
                    System.setProperty("lucee.extensions.install", "false");    
                } else {
                    System.setProperty("lucee.extensions.install", lucee_extensions_install);    
                }
                String lucee_controller_disabled = System.getenv("LUCEE_CONTROLLER_DISABLED");
                if (lucee_controller_disabled != null) {
                    System.setProperty("lucee.controller.disabled", lucee_controller_disabled);    
                }
                
                
                //felix.storage.clean?
                //felix configuration props: http://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-configuration-properties.html
                String felix_cache_locking = System.getenv("FELIX_CACHE_LOCKING");
                if (felix_cache_locking != null) {
                    System.setProperty("felix.cache.locking", felix_cache_locking);
                }
                String felix_log_level = System.getenv("FELIX_LOG_LEVEL");
                if (felix_log_level != null) {
                    System.setProperty("felix.log.level", felix_log_level);
                }
                String felix_cache_bufsize = System.getenv("FELIX_CACHE_BUFSIZE");
                if (felix_cache_bufsize != null) {
                    System.setProperty("felix.cache.bufsize", felix_cache_bufsize);
                }
                try {
                    new File("/tmp/lucee/web/").mkdirs();
                    StreamLambdaHandler.cfmlServlet = new CFMLServlet();
                    ServletConfig servletConfig = new CFMLServletConfig(new ServletContextWrapper(servletContext));
                    
                    cfmlServlet.init(servletConfig);
                    //CFMLEngine cfmlEngine = CFMLEngineFactory.getInstance(servletConfig);
                } catch (Throwable t) {
                    log("onStartup exception", t);
                }
                long startupComplete = System.currentTimeMillis();
                try {
                    //put System.out back to default PrintStream
                    System.setOut(systemOUT);
                } catch (Exception e) {
                    log("Error putting back system.out: ", e);
                }
                log("onStartup complete: " + (startupComplete-startTime));
            });
        } catch (ContainerInitializationException e) {
            // if we fail here. We re-throw the exception to force another cold start
            log("ContainerInitializationException: ", e);
            e.printStackTrace();
            
            throw new RuntimeException("Could not initialize the container", e);
        } finally {
            
        }
    }

    public static final void log(String msg) {
        StreamLambdaHandler.log(msg, null);
    }

    public static final void log(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("FuseLess: StreamLambdaHandler: ");
        sb.append(msg);
        if (t != null) {
            sb.append(" :: ");
            sb.append(t.getMessage());
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append(e.toString());
            }    
        }
        sb.append(10);
        logger.log(sb.toString());

    }

    public static final HttpServlet getCFMLServlet() {
        return StreamLambdaHandler.cfmlServlet;
    }

    public StreamLambdaHandler() {
        
    }

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        FuseLessContext ctx = new FuseLessContext(context);
        handler.proxyStream(inputStream, outputStream, ctx);
    }

    public void handleEventRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        EventLambdaHandler handler = new EventLambdaHandler();
        handler.handleRequest(inputStream, outputStream, context);
    }

    /**
     * Minimal no-op Lambda Context for driving a synthetic warmup request in
     * beforeCheckpoint, when there is no live invocation context available.
     * Unlike FuseLessContext, this does not wrap or delegate to a real Context.
     */
    private static final class PrimingContext implements Context {
        @Override
        public String getAwsRequestId() {
            return "crac-priming";
        }

        @Override
        public String getLogGroupName() {
            return null;
        }

        @Override
        public String getLogStreamName() {
            return null;
        }

        @Override
        public String getFunctionName() {
            return null;
        }

        @Override
        public String getFunctionVersion() {
            return null;
        }

        @Override
        public String getInvokedFunctionArn() {
            return null;
        }

        @Override
        public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 0;
        }

        @Override
        public LambdaLogger getLogger() {
            return logger;
        }
    }

}
