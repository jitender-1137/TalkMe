package com.neo.chat.service.impl;

import com.neo.chat.domain.BucketList;
import com.neo.chat.repository.BucketListRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit test for the extracted REQUIRES_NEW insert helper. */
@ExtendWith(MockitoExtension.class)
@DisplayName("BucketListRowCreator")
class BucketListRowCreatorTest {

    @Mock
    private BucketListRepository bucketListRepository;

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("persists a fresh list row bound to the chat uuid")
    void persistsRow() {
        BucketListRowCreator creator = new BucketListRowCreator(bucketListRepository);
        BucketList saved = BucketList.builder().chatUuid(CHAT_ID).build();
        when(bucketListRepository.save(any())).thenReturn(saved);

        BucketList result = creator.createInNewTx(CHAT_ID);

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<BucketList> cap = ArgumentCaptor.forClass(BucketList.class);
        verify(bucketListRepository).save(cap.capture());
        assertThat(cap.getValue().getChatUuid()).isEqualTo(CHAT_ID);
    }
}
