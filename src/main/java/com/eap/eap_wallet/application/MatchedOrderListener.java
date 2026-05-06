package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderMatchedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_MATCHED_QUEUE;

@Slf4j
@Component
public class MatchedOrderListener {

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @RabbitListener(queues = WALLET_ORDER_MATCHED_QUEUE)
    public void handleOrderMatched(OrderMatchedEvent event) {
        log.info("收到 OrderMatchedEvent: matchId={}, 買方={}, 賣方={}, 成交價={}, 數量={}",
                 event.getMatchId(), event.getBuyerId(), event.getSellerId(),
                 event.getDealPrice(), event.getAmount());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Integer dealCurrency = event.getDealPrice() * event.getAmount();
                    Integer originalLockedCurrency = event.getOriginBuyerPrice() * event.getAmount();

                    // === 買方處理 ===
                    WalletEntity buyerWallet = walletRepository.findByUserId(event.getBuyerId());
                    if (buyerWallet == null) {
                        log.error("找不到買方錢包: {}", event.getBuyerId());
                        throw new RuntimeException("買方錢包不存在: " + event.getBuyerId());
                    }

                    Integer refundCurrency = originalLockedCurrency - dealCurrency;

                    buyerWallet.setLockedCurrency(buyerWallet.getLockedCurrency() - originalLockedCurrency);
                    buyerWallet.setAvailableCurrency(buyerWallet.getAvailableCurrency() + refundCurrency);
                    buyerWallet.setAvailableAmount(buyerWallet.getAvailableAmount() + event.getAmount());

                    walletRepository.save(buyerWallet);

                    log.info("買方錢包更新完成: userId={}, 支付={}, 退還={}, 獲得電量={}",
                             event.getBuyerId(), dealCurrency, refundCurrency, event.getAmount());

                    // === 賣方處理 ===
                    WalletEntity sellerWallet = walletRepository.findByUserId(event.getSellerId());
                    if (sellerWallet == null) {
                        log.error("找不到賣方錢包: {}", event.getSellerId());
                        throw new RuntimeException("賣方錢包不存在: " + event.getSellerId());
                    }

                    sellerWallet.setLockedAmount(sellerWallet.getLockedAmount() - event.getAmount());
                    sellerWallet.setAvailableCurrency(sellerWallet.getAvailableCurrency() + dealCurrency);

                    walletRepository.save(sellerWallet);

                    log.info("賣方錢包更新完成: userId={}, 收入={}, 出售電量={}",
                             event.getSellerId(), dealCurrency, event.getAmount());
                });
                log.info("撮合處理完成 - matchId={}", event.getMatchId());
                break; // success, exit retry loop
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    log.error("撮合結算失敗，optimistic lock 衝突達 {} 次上限: matchId={}", maxRetries, event.getMatchId(), e);
                    throw e;
                }
                log.warn("Optimistic lock 衝突，重試 {}/{}: matchId={}", attempt, maxRetries, event.getMatchId());
            }
        }
    }
}
