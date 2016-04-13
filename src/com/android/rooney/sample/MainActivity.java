package com.android.rooney.sample;

import com.android.rooney.catchanr.AnrCatchManager;

import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class MainActivity extends Activity {
	
	private Button creatAnrBtn = null;
	
	private static final String TAG = "MainActivity";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		AnrCatchManager.getInstance().setContext(getApplicationContext());
		AnrCatchManager.getInstance().startAnrCatch();
        creatAnrBtn = (Button) findViewById(R.id.btn_anr);
        creatAnrBtn.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				for (;;) {
					Log.d(TAG, "creating ANR");
					try {
						Thread.sleep(1000L);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		AnrCatchManager.getInstance().stopAnrCatch();
	}

}
