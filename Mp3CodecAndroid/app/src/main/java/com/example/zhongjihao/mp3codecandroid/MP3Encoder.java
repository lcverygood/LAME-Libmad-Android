package com.example.zhongjihao.mp3codecandroid;

import android.media.AudioRecord;
import android.os.Looper;
import android.util.Log;
import android.os.Handler;
import android.os.Message;

import com.example.zhongjihao.mp3codecandroid.mp3codec.Mp3EncoderWrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by zhongjihao100@163.com on 18-8-12.
 **/

public class MP3Encoder extends Thread implements AudioGather.PcmCallback,AudioRecord.OnRecordPositionUpdateListener {
    private static final String TAG = "MP3Encoder";
    //用于存取待转换的PCM数据
    private LinkedBlockingQueue<PcmBuffer> audioQueue;
    private FileOutputStream mp3File;
    private byte[] mp3Buffer;
    private StopHandler handler;
    private CountDownLatch handlerInitLatch = new CountDownLatch(1);
    private static final int PROCESS_STOP = 1;
    private String outputDir;
    private String recordFileName;
    private boolean isEncodering = false;
    //输出MP3的码率
    public static final int BITRATE = 32;
    //mode = 0,1,2,3 = stereo, jstereo, dual channel (not supported), mono
    public static final int MODE = 3;
    /*
       recommended:
           2     near-best quality, not too slow
           5     good quality, fast
           7     ok quality, really fast
    */
    public static final int QUALITY = 7;

    public static class StopHandler extends Handler {
        WeakReference<MP3Encoder> sr;

        public StopHandler(MP3Encoder stateReceiver) {
            sr = new WeakReference<MP3Encoder>(stateReceiver);
        }

        @Override
        public void handleMessage(Message msg) {
            MP3Encoder codec = sr.get();
            if (codec == null) {
                return;
            }

            if (msg.what == PROCESS_STOP) {
                //录音停止后，将剩余的PCM数据转换完毕
                for (;codec.encoderData() > 0;);
                removeCallbacksAndMessages(null);
                codec.flush();
                codec.audioQueue.clear();
                Log.d(TAG, "=====zhongjihao======MP3编码线程开始退出...");
                getLooper().quit();
            }
        }
    }

    public MP3Encoder() {

    }

    public void setOutputPath(String dir, String fileName){
        this.outputDir = dir;
        this.recordFileName = fileName;
    }

    public void initMP3Encoder(int numChannels, int inSampleRate,int outSampleRate, int bitRate, int mode, int quality,int min_buffer_size) {
        File file = FileUtil.setOutPutFile(outputDir, recordFileName);
        if (file == null) {
            Log.e(TAG, "initMP3Encoder----outputDir: " + outputDir + "  fileName: " + recordFileName + "  create error");
            return;
        }
        Log.d(TAG, "initMP3Encoder");
        try {
            mp3File = new FileOutputStream(file);
        }catch (Exception e){
            e.printStackTrace();
        }
        //官方规定了计算公式：7200 + (1.25 * buffer_l.length)
        mp3Buffer =  new byte[(int) (7200 + (min_buffer_size/2 * 2 * 1.25))];
        Log.d(TAG,"mp3Buffer size: "+mp3Buffer.length+"   bufferSize: "+min_buffer_size/2);
        audioQueue = new LinkedBlockingQueue<>();
        Mp3EncoderWrap.newInstance().createEncoder();
        Mp3EncoderWrap.newInstance().initMp3Encoder(numChannels,inSampleRate,outSampleRate,bitRate,mode,quality);
    }

    @Override
    public void run() {
        isEncodering = true;
        Looper.prepare();
        handler = new StopHandler(this);
        handlerInitLatch.countDown();
        Looper.loop();
        isEncodering = false;
        Log.d(TAG, "=====zhongjihao======MP3编码线程已经退出...");
    }

    public Handler getHandler() {
        try {
            handlerInitLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.e(TAG, "Error when waiting handle to init");
        }
        return handler;
    }

    public void stopMP3Encoder() {
        handler.sendEmptyMessage(PROCESS_STOP);
    }

    public boolean isEncodering() {
        return isEncodering;
    }

    /**
     * 添加音频数据
     *
     * @param rawData
     */
    public void addPcmData(short[] rawData, int readSize) {
        try {
            Log.d(TAG, "======addPcmData===readSize: "+readSize);
            if (audioQueue != null)
                audioQueue.put(new PcmBuffer(rawData,readSize));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onMarkerReached(AudioRecord recorder) {
        // Do nothing
    }

    @Override
    public void onPeriodicNotification(AudioRecord recorder) {
        Log.d(TAG, "======onPeriodicNotification===");
        //由AudioRecord进行回调，满足帧数，通知数据编码
        encoderData();
    }

    //从缓存区audioQueue里获取待编码的PCM数据，编码为MP3数据,并写入文件
    private int encoderData() {
        if(audioQueue != null && !audioQueue.isEmpty()) {
            try {
                PcmBuffer data = audioQueue.take();
                short[] buffer = data.getData();
                int readSize = data.getReadSize();
                if (readSize > 0) {
                    int encodedSize =  Mp3EncoderWrap.newInstance().encodePcmDataToMp3(buffer, buffer, readSize, mp3Buffer);
                    Log.d(TAG, "===zhongjihao====Lame encoded size: " + encodedSize);
                    if (encodedSize > 0) {
                        try {
                            mp3File.write(mp3Buffer, 0, encodedSize);
                        } catch (IOException e) {
                            e.printStackTrace();
                            Log.e(TAG, "===zhongjihao====Unable to write to file");
                        }
                    }
                    return readSize;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    //Flush all data left in lame buffer to file
    private void flush() {
        try {
            final int flushResult = Mp3EncoderWrap.newInstance().encodeFlush(mp3Buffer);
            Log.d(TAG, "===zhongjihao====flush mp3Buffer: "+mp3Buffer+"  flush size: "+flushResult);
            if (flushResult > 0) {
                mp3File.write(mp3Buffer, 0, flushResult);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                mp3File.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.d(TAG, "===zhongjihao====destroy===mp3 encoder====");
            Mp3EncoderWrap.newInstance().destroyMp3Encoder();
        }
    }

    public String getMp3Path(){
        return outputDir+"/"+recordFileName;
    }
}
