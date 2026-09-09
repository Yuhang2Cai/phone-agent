package com.fandazi.seed;

import jakarta.persistence.*;

@Entity
@Table(name = "seed_address_metadata")
public class SeedAddressMetadataEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long addressId;
    @Column(nullable=false) private String detail;
    @Column(nullable=false) private boolean valid;
    protected SeedAddressMetadataEntity() {}
    public SeedAddressMetadataEntity(Long addressId, String detail, boolean valid) { this.addressId=addressId; this.detail=detail; this.valid=valid; }
}
