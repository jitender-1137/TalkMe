package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for account deletion. {@code password} re-authenticates the destructive
 * action for accounts that have a local password; OAuth-only accounts (no password)
 * may omit it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeleteAccountRequest {
    private String password;
}
