package com.starticket.account;

import com.starticket.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountLookup {

    private final UserRepository users;

    AccountLookup(UserRepository users) {
        this.users = users;
    }

    public long requireUserId(String username) {
        return users.findByUsernameOrEmail(username, username)
                .map(User::getId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }
}
