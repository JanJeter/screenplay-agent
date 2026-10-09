package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.service.AccountLifecycleService;
import com.urke.saasbackendstarter.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Legacy endpoints share the same hashed, one-use token and session-revocation flow. */
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {
    private final AccountLifecycleService accounts;
    @Override public void createResetToken(String email) { accounts.forgotPassword(email); }
    @Override public void resetPassword(String token, String newPassword) { accounts.resetPassword(token, newPassword); }
}
