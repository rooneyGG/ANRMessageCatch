package com.android.rooney.catchanr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.content.Context;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;


public class AnrCatchManager {
	
	public static final int MESSAGE_ANR_CATCHER = 155;
	
	private static final int MESSAGE_FIX_PATH = 156;
	
	private static final int MESSAGE_START_OBSERVER = 157;
	
	private static final int MESSAGE_STOP_OBSERVER = 158;
     
	private static final String TAG = "AnrCatchManager";
	
	//Trace文件目录
	private static final String TRACE_PATH = "/data/anr/";
	
	//Trace文件名
	private static final String TRACE_PATH_FILE = "/data/anr/traces.txt";

	//Trace文件名字
	private static final String TRACE_FILE = "trace";
	
	//匹配进程名
	private static final Pattern CMD_LINE = Pattern.compile("Cmd\\sline:\\s(\\S+)"); 
	
	//匹配进程结束信息
	private static final Pattern END_LINE = Pattern.compile("-{5}\\send\\s\\d+\\s-{5}");
	
	//匹配线程行
	private static final Pattern THREAD_LINE = Pattern.compile("\".+\"\\s(daemon\\s){0,1}prio=\\d+\\stid=\\d+\\s.*");
	
	//匹配So文件
	private static final Pattern SO_LINE = Pattern.compile("(\\S+).so");
	
	private static final Pattern VERTIC_LINE = Pattern.compile("^(\\s+)\\|(\\s+)(\\S+)");
	
	private static final Pattern WAITING_LINE = Pattern.compile("^(\\s+)-(\\s+)(\\S+)");
	
	private static AnrCatchManager mInstance = new AnrCatchManager();
	
	public TraceFileObserver mFileObserver = null;
	
	private HandlerThread mHThread = null;
	
	private Handler mTHandler = null;
	
	private Context mContext = null;
	
	private String mObserverPath = TRACE_PATH;
	
	private Map<String, String> messageStore = new HashMap<String,String>();
	
	 private class THandler extends Handler {

		public THandler(Looper loop) {
			super(loop);
		}
		
		@Override
		public void handleMessage(Message msg) {
			
			switch(msg.what) {
			
			    case MESSAGE_ANR_CATCHER: {
			    	reaperAnrInfo((String)msg.obj);
				    break;
			    }
			    
			    case MESSAGE_FIX_PATH: {
					fetchObserverPath();
			    	break;
			    }
			    
			    case MESSAGE_START_OBSERVER: {
			    	startFileObserver();
			    	break;
			    }
			    
			    case MESSAGE_STOP_OBSERVER: {
			    	stopFileObserver();
			    	break;
			    }
			    
				default: {
					break;
				}
			}
			
			super.handleMessage(msg);
		}
		
	}
	
	private AnrCatchManager() {
		mHThread = new HandlerThread("anr_catcher");
		mHThread.start();
		mTHandler = new THandler(mHThread.getLooper());
		mTHandler.obtainMessage(MESSAGE_FIX_PATH).sendToTarget(); //判别MTK手机，
	}
	
	public void setContext(Context context) {
		mContext = context;
	}
	
	/**
	 * 不同平台，不同OS版本监控的文件不同
	 */
	private void fetchObserverPath() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { //6.0系统直接高通和MTK平台都只能监控确定文件
			mObserverPath = TRACE_PATH_FILE;
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			File po = new File("/proc/"); //判断是否是高通平台，如果使用某属性去判断，可能不同厂商获取解决方案之后有修改，不靠谱
			File[] ffs = po.listFiles(new FilenameFilter() {				
				@Override
				public boolean accept(File dir, String filename) {
					return filename.toLowerCase().startsWith("mtk_");
				}
			});
			if (ffs != null && ffs.length > 0) {//mTK平台
				mObserverPath = TRACE_PATH;
			} else {
				mObserverPath = TRACE_PATH_FILE;
			}
		} else {
			mObserverPath = TRACE_PATH;
		}
	}
	
	public static AnrCatchManager getInstance() {
		return mInstance;
	}
	
	private boolean startFileObserver() {
		if (mFileObserver == null) {
			mFileObserver = new TraceFileObserver(mObserverPath, FileObserver.CLOSE_WRITE);
		}
		mFileObserver.setHandler(mTHandler);
		try {
			mFileObserver.startWatching();
			return true;
		} catch(Exception e) {
			return false;
		}
	}
	
	public boolean startAnrCatch() {
		mTHandler.obtainMessage(MESSAGE_START_OBSERVER).sendToTarget();
		return true;
	}
	
	private void stopFileObserver() {
		try {
		    mFileObserver.stopWatching();
		} catch (Exception e) {
			
		} finally {
		    mFileObserver = null;
		}
		mHThread.quitSafely();
		mHThread = null;
		mTHandler = null;
		mInstance = null;
		mContext = null;
	}
	
	public void stopAnrCatch() {
		mTHandler.obtainMessage(MESSAGE_STOP_OBSERVER).sendToTarget();
	}
	

	public void reaperAnrInfo(String path) {
		BufferedReader bufferReader = null;
		StringBuilder builder = new StringBuilder();
		int readCount = 0;
		boolean findCmdline = false;
		boolean findEndline = false;
		boolean findmainline = false;
		boolean findThdline = false;
		path = fixPathForDiffPlatform(path);
		if (!isTraceFileExists(path)) {
			Log.d(TAG, TRACE_FILE + " is not exist!");
			return;
		}
		delayReadMessage(500);
		try {
			bufferReader = new BufferedReader(new FileReader(TRACE_PATH + path));
			String readline = null;
			while ((readline = bufferReader.readLine()) != null) {
				readCount++;
				findCmdline = findCmdPackage(readline, findCmdline);
				boolean[] results = findMainThread(readline, findCmdline, builder, findmainline);
				findmainline = results[0];
				findThdline = results[1];
				findEndline = findPackageEnd(readline, findCmdline);
				boolean[] findFlags = new boolean[]{findCmdline, findmainline, findThdline};
				if (findCmdline && findEndline) { //已找到需要的数据
					break;
				}
				boolean result = collectTraceInfo(readline, builder, findFlags);
				if (result) {
					continue;
				}
				if (findCmdline) {
					messageStore.put("trace", builder.toString());
				    Log.d(TAG, "fetcher trace!");
				    messageStore.put("reason", fetchAnrMessage());
				    Log.d(TAG, "fetcher reason!");
				    break;
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			builder.delete(0, builder.length());
			if(findCmdline || 
					(!findCmdline && (readCount > 0))) { //是应用ANR，或者是其他应用ANR?
				mTHandler.removeMessages(MESSAGE_ANR_CATCHER); //去掉其他MESSAGE_ANR_CATCHER事件
			}
			if (!findCmdline) {
				messageStore.clear();
			}
			Log.d(TAG, "findCmdline = " + findCmdline + " readCount = " + readCount);
			if (bufferReader != null) {
				try {
					bufferReader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
	}
	
	private boolean isTraceFileExists(String path) {
		String fileFath = TRACE_PATH + path;
		File traceFile = new File(fileFath);
		if (fileFath.contains(TRACE_FILE) && 
				traceFile.exists() && traceFile.canRead()) {
			return true;
		}
		return false;
	}
	
	private boolean findCmdPackage(String readline, boolean findCmdline) {
		Matcher matcher = CMD_LINE.matcher(readline);
		if (!findCmdline && matcher.find()) {
			if (readline.contains(
					mContext.getApplicationContext().getPackageName())) {
				findCmdline = true;
			}
		}
		return findCmdline;
	}
	
	private boolean[] findMainThread(String readline, 
			boolean findCmdline,StringBuilder builder, boolean findmainline) {
		boolean[] results = new boolean[]{findmainline, false};
		Matcher matcher = THREAD_LINE.matcher(readline);
		if (findCmdline && matcher.find()) {
			if (findmainline) {//提取stack信息
				messageStore.put("stack", builder.toString());
				builder.delete(0, builder.length()); //去除已有的数据，线程运行情况，得加上主线程的stack
				findmainline = false;
			}
			if (readline.contains("main")) {
				findmainline = true;
			}
			results[1] = true;
		}
		return results;
	}
	
	/**
	 * 寻找本应用末尾
	 * @param readline
	 * @param findCmdline
	 * @return
	 */
	private boolean findPackageEnd(String readline, boolean findCmdline) {
		boolean findEndline = false;
		Matcher matcher = END_LINE.matcher(readline);
		if (findCmdline && matcher.find()) { //表明找到本应用末尾
			findEndline = true;
		}
		return findEndline;
	}
	
	/**
	 * 获取信息
	 * @param readline
	 * @param builder
	 * @param findFlags
	 */
	private boolean collectTraceInfo(String readline, StringBuilder builder,
			boolean[] findFlags) {
		boolean findCmdline = findFlags[0];
		boolean findmainline = findFlags[1]; 
		boolean findThdline = findFlags[2];
		Matcher matcher = null;
		if (findCmdline && findThdline) { //表明是本应用
			matcher = VERTIC_LINE.matcher(readline);
			if (matcher.find()) {
				return true;
			}
			matcher = WAITING_LINE.matcher(readline);
			if (matcher.find()) {
				return true;
			}
			matcher = SO_LINE.matcher(readline);
			if (matcher.find()) {
				if (findmainline) {
					builder.append(readline); //主线程需要打出所以调用信息
					builder.append("\n");
				} else {
					return true;
				}
			} else {
				builder.append(readline);
				builder.append("\n");
			}
		}
		return false;
	}
	
	/**
	 * 解析ProcessErrorInfo
	 * @param paramMap
	 */
	private String fetchAnrMessage() {
		ActivityManager manager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
		int count = 0; //10秒中，没有取到CPU使用信息，直接退出；
		while(true) {
			List<ProcessErrorStateInfo> lists = manager.getProcessesInErrorState();
			if (lists != null) {
				for (ProcessErrorStateInfo info : lists) {
					if (info.condition == ProcessErrorStateInfo.NOT_RESPONDING) {
						messageStore.put("AnrMessage", info.longMsg); //提取Anr进程CPU占用信息
						return info.shortMsg;
					}
				}
			}
			if (count++ >= 20) {
				break;
			}
			Log.d(TAG, "waiting for process error Info!");
	        delayReadMessage(500);
		}
		return null;
	}
	
	private void delayReadMessage(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}
	
	/**
	 * 兼容MTK或者高通平台的不同版本，也许还有没发现的兼容问题
	 * @param path
	 * @return
	 */
	private String fixPathForDiffPlatform(String path) {
		if (path == null || "binderinfo".equals(path.toLowerCase())) { //兼容MTK平台和6.0系统
			path = "traces.txt";
		}
		return path;
	}
}
