package com.umaai.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btn_start_float);
        btn.setOnClickListener(v -> {
            Toast.makeText(this, "App 运行成功！", Toast.LENGTH_SHORT).show();
        });
    }
}