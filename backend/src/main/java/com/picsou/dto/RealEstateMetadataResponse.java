package com.picsou.dto;

import com.picsou.model.PropertyCategory;
import com.picsou.model.RealEstateMetadata;
import com.picsou.model.ValuationMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RealEstateMetadataResponse(
    BigDecimal purchasePrice,
    LocalDate purchaseDate,
    BigDecimal agencyFees,
    BigDecimal notaryFees,
    BigDecimal worksCost,
    /** Purchase price plus every acquisition fee — what gain/loss is measured against. */
    BigDecimal costBasis,

    String propertyType,
    PropertyCategory category,
    String description,

    String address,
    String postalCode,
    String city,
    String country,
    /** Set once the address has been geocoded; its presence is what enables valuation. */
    String inseeCode,
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal geocodeScore,
    Instant geocodedAt,

    BigDecimal surfaceArea,
    BigDecimal landArea,
    Short constructionYear,
    Short rooms,
    Short bedrooms,
    Short bathrooms,
    Short floorNumber,
    Short floorsTotal,
    Boolean hasElevator,
    Short garageCount,
    Short parkingCount,
    Boolean hasGarden,
    Boolean hasTerrace,
    Boolean hasBalcony,
    String energyClass,

    ValuationMode valuationMode,
    BigDecimal rentalIncome
) {
    public static RealEstateMetadataResponse from(RealEstateMetadata m) {
        return new RealEstateMetadataResponse(
            m.getPurchasePrice(),
            m.getPurchaseDate(),
            m.getAgencyFees(),
            m.getNotaryFees(),
            m.getWorksCost(),
            m.costBasis(),
            m.getPropertyType(),
            m.getCategory(),
            m.getDescription(),
            m.getAddress(),
            m.getPostalCode(),
            m.getCity(),
            m.getCountry(),
            m.getInseeCode(),
            m.getLatitude(),
            m.getLongitude(),
            m.getGeocodeScore(),
            m.getGeocodedAt(),
            m.getSurfaceArea(),
            m.getLandArea(),
            m.getConstructionYear(),
            m.getRooms(),
            m.getBedrooms(),
            m.getBathrooms(),
            m.getFloorNumber(),
            m.getFloorsTotal(),
            m.getHasElevator(),
            m.getGarageCount(),
            m.getParkingCount(),
            m.getHasGarden(),
            m.getHasTerrace(),
            m.getHasBalcony(),
            m.getEnergyClass(),
            m.getValuationMode(),
            m.getRentalIncome()
        );
    }
}
