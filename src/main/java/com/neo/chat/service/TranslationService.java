package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.TranslateBatchRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.TranslateBatchResponse;
import com.neo.chat.dto.response.TranslateResponse;

/**
 * Stateless translation of already-decrypted plaintext supplied by the client
 * (feature INSTANT_TRANSLATE). No server-side message decryption, no persistence.
 */
public interface TranslationService {

    /**
     * Translate {@code req.text} into {@code req.target}.
     *
     * @param user the caller (used for the per-user daily cap)
     * @param req  the plaintext + target/source language
     * @return the translation (fails open by echoing the input on provider failure)
     */
    TranslateResponse translate(User user, TranslateRequest req);

    /**
     * Translate many texts into a single target in one call. Cache hits are served free;
     * only the uncached remainder makes one provider call (Azure native array batch, MyMemory
     * per-item fallback) and counts as a SINGLE unit against the caller's daily cap.
     *
     * @param user the caller (the whole batch consumes at most one daily-cap unit)
     * @param req  the items (id + text), shared target, optional source
     * @return one result per item, in order, each tagged with its input id
     */
    TranslateBatchResponse translateBatch(User user, TranslateBatchRequest req);
}
