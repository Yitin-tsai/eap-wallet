package com.eap.eap_wallet.configuration.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eap.eap_wallet.domain.entity.WalletEntity;

public interface WalletRepository extends JpaRepository<WalletEntity, Long> {

    
    WalletEntity findByUserId(UUID userId);
    
   
    
} 