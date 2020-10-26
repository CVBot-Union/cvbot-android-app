package com.cvbotunion.cvtwipush.Fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.cvbotunion.cvtwipush.Activities.Timeline;
import com.cvbotunion.cvtwipush.R;
import com.cvbotunion.cvtwipush.Utils.RSACrypto;
import com.google.android.material.snackbar.Snackbar;

public class LoginFragment extends Fragment {
    private EditText usernameText;
    private EditText passwordText;
    private Button loginButton;

    private String publicKey;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initPublicKey();
        initView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.login_fragment, container, false);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 备用
    }

    private void initPublicKey() {
        new Thread(() -> {
            try {
                if(Timeline.connection.webService==null) {
                    synchronized (Timeline.connection.flag) {
                        Timeline.connection.flag.wait();
                    }
                }
                publicKey = Timeline.connection.webService.queryPublicKey();
            } catch (Exception e) {
                e.printStackTrace();
                // TODO 获取失败的处理
            }
        });
    }

    private void initView() {
        View parentView = getView();
        usernameText = parentView.findViewById(R.id.login_username_text);
        passwordText = parentView.findViewById(R.id.login_password_text);
        loginButton = parentView.findViewById(R.id.login_btn);

        passwordText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) { }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if(editable.length()!=0) {
                    loginButton.setClickable(true);
                    loginButton.setBackgroundColor(getContext().getColor(R.color.colorPrimary));
                } else {
                    loginButton.setClickable(false);
                    loginButton.setBackgroundColor(getContext().getColor(R.color.colorGray));
                }
            }
        });
        loginButton.setClickable(false);
        loginButton.setBackgroundColor(getContext().getColor(R.color.colorGray));
        loginButton.setOnClickListener(view -> {
            loginButton.setClickable(false);
            final String username = usernameText.getText().toString();
            final String password = RSACrypto.getInstance().encrypt(passwordText.getText().toString(), publicKey);
            if(password==null) {
                // TODO
            } else if(username.length()!=0 && !username.contains(" ") && !password.contains(" ")) {
                new Thread(() -> {
                    try {
                        if(Timeline.connection.webService==null) {
                            synchronized (Timeline.connection.flag) {
                                Timeline.connection.flag.wait();
                            }
                        }
                        Timeline.connection.webService.login(username, password);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            } else {
                Snackbar.make(view, "用户名/密码为空或包含空格", 1000);
            }
        });
    }
}