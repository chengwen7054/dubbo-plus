package net.dubboclub.akka.remoting.actor;

import akka.actor.*;
import akka.japi.Creator;
import akka.routing.*;
import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.RpcResult;
import net.dubboclub.akka.remoting.codec.AkkaCodec;
import net.dubboclub.akka.remoting.message.Request;
import net.dubboclub.akka.remoting.message.RequestPackage;
import net.dubboclub.akka.remoting.message.Response;
import net.dubboclub.akka.remoting.message.ResponsePackage;
import net.dubboclub.akka.remoting.utils.Utils;

import java.io.IOException;

/**
 * Created by bieber on 2015/7/8.
 */
public class ConsumeServiceLookup extends RouterActor {

    private volatile int currentWorkerSize = 0;

    private Class<?> type;

    private URL url;



    public ConsumeServiceLookup(Class<?> type, URL url) {
        super(url);
        this.type = type;
        this.url = url;
    }

    @Override
    public void onReceive(Object o) throws Exception {
        if(o instanceof Request){
            if(currentWorkerSize>=MAX_WORKER_SIZE){
                router.route(o,getSender());
            }else{
                ActorRef worker = getContext().actorOf(Props.create(new WorkerCreator(url)));
                router=router.addRoutee(new ActorRefRoutee(worker));
                currentWorkerSize++;
                worker.tell(o,getSender());
            }
        }else{
            unhandled(o);
        }
    }

     static class WorkerCreator implements Creator<Worker>{
         private URL url;
         public WorkerCreator(URL url){
            this.url = url;
         }
        @Override
        public Worker create() throws Exception {
            return new Worker(url);
        }
    }
    static class Worker extends UntypedActor{

        private URL url;

        protected Worker(URL url){
            this.url = url;
        }

        @Override
        public void onReceive(Object o) throws Exception {
            if(o instanceof Request){
                Request request = (Request) o;
                //invoke provider
                String path = Utils.generateRemoteActorPath(url,request.getActorName());
                getContext().actorSelection(path).tell(new Identify(request),getSelf());
            }else if(o instanceof ActorIdentity){
                ActorIdentity identity = (ActorIdentity) o;
                Request request = (Request) identity.correlationId();
                if(identity.getRef()==null){
                    Response response = new Response(request.getRequestId());
                    response.setStatus(Response.SERVICE_NOT_FOUND);
                    AkkaFuture.receive(response);
                }else{
                    try{
                        RequestPackage requestPackage = new RequestPackage(request.getRequestId(),AkkaCodec.encode(url,request).array());
                        identity.getRef().tell(requestPackage,getSelf());
                    }catch (IOException e){
                        Response response = new Response(request.getRequestId());
                        response.setStatus(Response.CLIENT_ERROR);
                        RpcResult result = new RpcResult();
                        result.setException(e);
                        response.setResult(result);
                        AkkaFuture.receive(response);
                    }
                }
            }else if(o instanceof ResponsePackage){
                ResponsePackage responsePackage = (ResponsePackage) o;
                try{
                    Response response = (Response) AkkaCodec.decode(url, responsePackage.getResponseMessage());
                    AkkaFuture.receive(response);
                }catch (IOException e){
                    Response response = new Response(responsePackage.getId());
                    response.setStatus(Response.CLIENT_ERROR);
                    RpcResult result = new RpcResult();
                    result.setException(e);
                    response.setResult(result);
                    AkkaFuture.receive(response);
                }
            }else{
                unhandled(o);
            }
        }
    }

}