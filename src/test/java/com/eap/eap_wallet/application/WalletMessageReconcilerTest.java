package com.eap.eap_wallet.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletMessageReconcilerTest {

    @Mock WalletMessageInbox inbox;
    @Mock WalletMessageProcessor processor;
    @Mock WalletMessageErrorClassifier classifier;

    private WalletMessageReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WalletMessageReconciler(inbox, processor, classifier,
                10, 30_000, 5, 250, 30_000);
    }

    @Test
    void transientFailure_shouldScheduleDurableRetry() {
        WalletMessageInbox.InboxEntry entry = entry(1);
        RuntimeException failure = new RuntimeException("database unavailable");
        when(inbox.claimRetryable(eq(10), anyString(), eq(30_000L))).thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(eq(entry), anyString());
        when(classifier.classify(failure)).thenReturn(
                new WalletMessageErrorClassifier.Classification(true, "TRANSIENT_DATABASE"));

        reconciler.reconcile();

        verify(inbox).reschedule(eq(entry), anyString(), eq("FAILED_RETRYABLE"),
                eq("TRANSIENT_DATABASE"), eq(failure), anyLong());
    }

    @Test
    void assetInvariantFailure_shouldBecomePermanentWithoutRetry() {
        WalletMessageInbox.InboxEntry entry = entry(5);
        WalletAssetConsistencyException failure =
                new WalletAssetConsistencyException("release exceeds locked asset");
        when(inbox.claimRetryable(eq(10), anyString(), eq(30_000L))).thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(eq(entry), anyString());
        when(classifier.classify(failure)).thenReturn(
                new WalletMessageErrorClassifier.Classification(false, "PERMANENT_ASSET_INVARIANT"));

        reconciler.reconcile();

        verify(inbox).markPermanent(eq(entry), anyString(),
                eq("PERMANENT_ASSET_INVARIANT"), eq(failure));
    }

    @Test
    void unknownFailureAtBudget_shouldBecomePermanent() {
        WalletMessageInbox.InboxEntry entry = entry(5);
        RuntimeException failure = new RuntimeException("unknown");
        when(inbox.claimRetryable(eq(10), anyString(), eq(30_000L))).thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(eq(entry), anyString());
        when(classifier.classify(failure)).thenReturn(
                new WalletMessageErrorClassifier.Classification(true, "UNKNOWN_RETRYABLE"));

        reconciler.reconcile();

        verify(inbox).markPermanent(eq(entry), anyString(), eq("UNKNOWN_RETRYABLE"), eq(failure));
    }

    private WalletMessageInbox.InboxEntry entry(int attempt) {
        return new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.ORDER_SUBMITTED,
                UUID.randomUUID(),
                "{}",
                attempt);
    }
}
