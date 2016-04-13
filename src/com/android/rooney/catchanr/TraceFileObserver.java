package com.android.rooney.catchanr;

import android.os.FileObserver;
import android.os.Handler;

public class TraceFileObserver extends FileObserver {
	
	private Handler mHandler = null;

	public TraceFileObserver(String path) {
		super(path);
	}
	
	public TraceFileObserver(String path, int mask) {
		super(path, mask);
	}
	
	public void setHandler(Handler handler) {
		mHandler = handler;
	}

	@Override
	public void onEvent(int event, String path) {
		mHandler.obtainMessage(AnrCatchManager.MESSAGE_ANR_CATCHER, path).sendToTarget();
	}
	
}