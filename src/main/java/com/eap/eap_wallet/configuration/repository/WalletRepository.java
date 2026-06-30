package com.eap.eap_wallet.configuration.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eap.eap_wallet.domain.entity.WalletEntity;

public interface WalletRepository extends JpaRepository<WalletEntity, Long> {

    
    WalletEntity findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    @Modifying
    @Query(value = """
            UPDATE wallet_service.wallets
            SET available_currency = available_currency - :requiredCurrency,
                locked_currency = locked_currency + :requiredCurrency,
                version = version + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND available_currency >= :requiredCurrency
            """, nativeQuery = true)
    int reserveCurrencyForBuy(
            @Param("userId") UUID userId,
            @Param("requiredCurrency") int requiredCurrency);

    @Modifying
    @Query(value = """
            UPDATE wallet_service.wallets
            SET available_amount = available_amount - :requiredAmount,
                locked_amount = locked_amount + :requiredAmount,
                version = version + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND available_amount >= :requiredAmount
            """, nativeQuery = true)
    int reserveAmountForSell(
            @Param("userId") UUID userId,
            @Param("requiredAmount") int requiredAmount);

    @Modifying
    @Query(value = """
            UPDATE wallet_service.wallets
            SET locked_currency = locked_currency - :lockedCurrency,
                available_currency = available_currency + :refundCurrency,
                available_amount = available_amount + :quantity,
                version = version + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND locked_currency >= :lockedCurrency
            """, nativeQuery = true)
    int settleTradeForBuyer(
            @Param("userId") UUID userId,
            @Param("lockedCurrency") int lockedCurrency,
            @Param("refundCurrency") int refundCurrency,
            @Param("quantity") int quantity);

    @Modifying
    @Query(value = """
            UPDATE wallet_service.wallets
            SET locked_amount = locked_amount - :quantity,
                available_currency = available_currency + :dealCurrency,
                version = version + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND locked_amount >= :quantity
            """, nativeQuery = true)
    int settleTradeForSeller(
            @Param("userId") UUID userId,
            @Param("quantity") int quantity,
            @Param("dealCurrency") int dealCurrency);
} 
