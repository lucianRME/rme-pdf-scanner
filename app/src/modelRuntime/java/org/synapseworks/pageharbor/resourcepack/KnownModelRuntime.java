package org.synapseworks.pageharbor.resourcepack;
import ai.onnxruntime.*;
import ai.djl.huggingface.tokenizers.jni.TokenizersLibrary;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import android.os.SystemClock;
public final class KnownModelRuntime implements AutoCloseable {
 private final JSONObject report=new JSONObject();private final Client client;private AtomicBoolean cancellation=new AtomicBoolean(false);
 private static void require(boolean ok){if(!ok)throw new IllegalStateException("MODEL_CHECK_DECLINED");}
 private static String text(File f)throws Exception{require(f.length()<=8*1024*1024);return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
 public KnownModelRuntime(File root) throws Exception { this(root, ""); }
 public KnownModelRuntime(File root, String direction) throws Exception {
  require(direction.isEmpty() || direction.equals("en-ga") || direction.equals("ga-en"));
  long started = SystemClock.elapsedRealtime();
  Client loaded = new Client(root, direction.isEmpty() ? "" : direction + "-");
  if (SystemClock.elapsedRealtime() - started > 10000) {
   loaded.close();
   throw new IllegalStateException("MODEL_LOAD_LIMIT");
  }
  client = loaded;
 }
 public String check(AtomicBoolean cancelled)throws Exception{
  require(client.prefix.isEmpty());
  cancellation=cancelled;String[] inputs={"Hello world.","The document is on the table."};String[] expected={"Bună, lume.","Documentul este pe masă."};
  for(int i=0;i<2;i++){if(cancelled.get())throw new CancellationException();require(translate(inputs[i],cancelled).equals(expected[i]));}
  if(cancelled.get())throw new CancellationException();return "Frozen English → Romanian check passed (2/2). Bună, lume. Documentul este pe masă.";
 }
 public String translate(String input,AtomicBoolean cancelled)throws Exception{
  cancellation=cancelled;if(cancelled.get())throw new CancellationException();long started=SystemClock.elapsedRealtime();String output=client.translate(input,false);
  if(cancelled.get())throw new CancellationException();if(SystemClock.elapsedRealtime()-started>=20000)throw new IllegalStateException("SEGMENT_TIME_LIMIT");return output;
 }
 public boolean fits(String input)throws Exception{try{client.encode(input);return true;}catch(IllegalStateException declined){return false;}}
 public void close()throws Exception{client.close();}
    private final class Client implements AutoCloseable {
        final File root;
        final String prefix;
        final int vocabularySize;
        final int padId;
        OrtEnvironment env;final TokenizersLibrary lib=TokenizersLibrary.LIB;
        long tokenizer;OrtSession encoder,decoder;JSONArray mapping;JSONObject target;String[] vocabulary;
        long cancelLatencyMs;boolean requestDuringRun, nativeTermination;
        Client(File source, String pairPrefix) throws Exception {
            root=source;
            prefix=pairPrefix;
            vocabularySize=prefix.isEmpty() ? 59543 : 56683;
            padId=vocabularySize-1;
            // The default AAR's provider and HTTP bridge are absent from this probe.
            try {
                android.system.Os.setenv("TOKENIZERS_PARALLELISM","false",true);
                android.system.Os.setenv("RAYON_NUM_THREADS","2",true);
                System.loadLibrary("djl_tokenizer");
                env=OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL,"m36-private-probe");env.setTelemetry(false);report.put("runtimeVersion",env.getVersion());
                tokenizer=lib.createTokenizerFromString(text(new File(root,prefix+"tokenizer-source-adapter.json")));require(tokenizer!=0);
                
                mapping=new JSONArray(text(new File(root,prefix+"source-to-model-ids.json")));target=new JSONObject(text(new File(root,prefix+"target-decoder-data.json")));
                JSONObject vocab=new JSONObject(text(new File(root,prefix+"vocab.json")));vocabulary=new String[vocab.length()];for(Iterator<String> it=vocab.keys();it.hasNext();){String t=it.next();vocabulary[vocab.getInt(t)]=t;}
                require(vocabulary.length==vocabularySize&&"</s>".equals(vocabulary[0])&&"<unk>".equals(vocabulary[1])&&"<pad>".equals(vocabulary[padId]));
                try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){
                    opts.addConfigEntry("mlas.disable_kleidiai","1");
                    opts.setIntraOpNumThreads(2);opts.setInterOpNumThreads(1);opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    encoder=env.createSession(new File(root,prefix+"encoder_model_quantized.onnx").getPath(),opts);
                    decoder=env.createSession(new File(root,prefix+"decoder_model_quantized.onnx").getPath(),opts);
                }
                require(encoder.getInputNames().equals(new HashSet<>(Arrays.asList("input_ids","attention_mask"))));
                require(decoder.getInputNames().equals(new HashSet<>(Arrays.asList("input_ids","encoder_hidden_states","encoder_attention_mask"))));
            } catch(Exception|Error e){close();throw e;}
        }
        long[] encode(String input) throws Exception {
            require(input.getBytes(StandardCharsets.UTF_8).length<=512);long handle=lib.encode(tokenizer,input,false);
            try{long[] ids=lib.getTokenIds(handle);require(ids.length+1<=96);long[] out=new long[ids.length+1];for(int i=0;i<ids.length;i++){out[i]=mapping.getLong((int)ids[i]);require(out[i]!=1);}out[ids.length]=0;return out;}
            finally{lib.deleteEncoding(handle);}
        }
        String decodePieces(List<String> pieces) throws Exception {
            JSONObject types=target.getJSONObject("pieceTypes");StringBuilder out=new StringBuilder();
            for(String piece:pieces){int type=types.optInt(piece,2);if(type==3)continue;String part;
                if(type==2)part=piece.equals("<unk>")?target.getString("unkSurface"):piece;
                else{part=piece.replace('▁',' ');if(out.length()==0&&target.getBoolean("addDummyPrefix")&&part.startsWith(" "))part=part.substring(1);}
                out.append(part);
            }return out.toString();
        }
        String decode(List<Long> ids) throws Exception {List<String> pieces=new ArrayList<>();for(long id:ids){require(id>=0&&id<vocabulary.length);if(id!=0&&id!=padId)pieces.add(vocabulary[(int)id]);}return decodePieces(pieces);}
        String translate(String input,boolean cancel) throws Exception {
            long start=SystemClock.elapsedRealtime();long[] ids=encode(input),ones=new long[ids.length];Arrays.fill(ones,1);
            AtomicBoolean requested=new AtomicBoolean(false),active=new AtomicBoolean(false);AtomicLong requestedAt=new AtomicLong();
            ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();OrtSession.RunOptions run=new OrtSession.RunOptions();
            try(OnnxTensor inputTensor=OnnxTensor.createTensor(env,LongBuffer.wrap(ids),new long[]{1,ids.length});OnnxTensor mask=OnnxTensor.createTensor(env,LongBuffer.wrap(ones),new long[]{1,ids.length})){
                if(cancel)timer.schedule(()->{requestedAt.set(SystemClock.elapsedRealtime());requestDuringRun=active.get();requested.set(true);try{run.setTerminate(true);}catch(OrtException e){throw new IllegalStateException("CANCEL_CONTROL_FAILED");}},1,TimeUnit.MILLISECONDS);
                Map<String,OnnxTensor> feed=new HashMap<>();feed.put("input_ids",inputTensor);feed.put("attention_mask",mask);active.set(true);
                try(OrtSession.Result encoded=encoder.run(feed,run)){
                    active.set(false);OnnxTensor hidden=(OnnxTensor)encoded.get(0);List<Long> output=new ArrayList<>();List<Long> prefix=new ArrayList<>();prefix.add((long)padId);boolean eos=false;
                    while(output.size()<64){
                        if(requested.get() || cancellation.get())throw new CancellationException();if(SystemClock.elapsedRealtime()-start>=20000)throw new IllegalStateException("SEGMENT_TIME_LIMIT");
                        long[] next=new long[prefix.size()];for(int i=0;i<next.length;i++)next[i]=prefix.get(i);
                        try(OnnxTensor tokens=OnnxTensor.createTensor(env,LongBuffer.wrap(next),new long[]{1,next.length})){
                            Map<String,OnnxTensor> inputs=new HashMap<>();inputs.put("input_ids",tokens);inputs.put("encoder_hidden_states",hidden);inputs.put("encoder_attention_mask",mask);active.set(true);
                            try(OrtSession.Result result=decoder.run(inputs,Collections.singleton("logits"),run)){
                                active.set(false);OnnxTensor tensor=(OnnxTensor)result.get(0);java.nio.FloatBuffer logits=tensor.getFloatBuffer();require(logits.remaining()==next.length*vocabularySize);
                                int offset=(next.length-1)*vocabularySize,best=0;float maximum=-Float.MAX_VALUE;
                                for(int i=0;i<padId;i++){float score=logits.get(offset+i);require(Float.isFinite(score));if(score>maximum){maximum=score;best=i;}}
                                if(best==0){eos=true;break;}require(best!=1);output.add((long)best);prefix.add((long)best);
                            }
                        }
                    }
                    if(!eos)throw new IllegalStateException("MISSING_EOS_OR_OUTPUT_LIMIT");require(output.size()>0);String translated=decode(output);require(!translated.isEmpty());
                    return translated;
                }
            }catch(CancellationException e){if(cancellation.get())throw e;require(cancel&&requested.get());cancelLatencyMs=SystemClock.elapsedRealtime()-requestedAt.get();throw e;}
            catch(OrtException e){if(cancel&&requested.get()&&e.getMessage()!=null&&e.getMessage().toLowerCase(Locale.ROOT).contains("terminate")){nativeTermination=true;cancelLatencyMs=SystemClock.elapsedRealtime()-requestedAt.get();throw new CancellationException();}throw e;}
            finally{active.set(false);timer.shutdownNow();require(timer.awaitTermination(2,TimeUnit.SECONDS));run.close();}
        }
        public void close() throws Exception {
            try{if(decoder!=null){decoder.close();decoder=null;}}finally{try{if(encoder!=null){encoder.close();encoder=null;}}finally{try{if(tokenizer!=0){lib.deleteTokenizer(tokenizer);tokenizer=0;}}finally{mapping=null;target=null;vocabulary=null;}}}
        }
    }

}