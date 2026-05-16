package io.salad109.conjunctiondetector.spacetrack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * OMM (Orbit Mean-Elements Message) record from Space-Track GP class.
 * Field names match the Space-Track JSON response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OmmRecord(
        @JsonProperty("NORAD_CAT_ID") Integer noradCatId,
        @JsonProperty("OBJECT_NAME") String objectName,
        @JsonProperty("OBJECT_ID") String objectId,
        @JsonProperty("OBJECT_TYPE") String objectType,
        @JsonProperty("CLASSIFICATION_TYPE") String classificationType,
        @JsonProperty("COUNTRY_CODE") String countryCode,
        @JsonProperty("LAUNCH_DATE") LocalDate launchDate,
        @JsonProperty("SITE") String site,
        @JsonProperty("DECAY_DATE") LocalDate decayDate,
        @JsonProperty("EPOCH") LocalDateTime epoch,
        @JsonProperty("CREATION_DATE") LocalDateTime creationDate,
        @JsonProperty("TLE_LINE0") String tleLine0,
        @JsonProperty("TLE_LINE1") String tleLine1,
        @JsonProperty("TLE_LINE2") String tleLine2,
        @JsonProperty("MEAN_MOTION") BigDecimal meanMotion,
        @JsonProperty("MEAN_MOTION_DOT") BigDecimal meanMotionDot,
        @JsonProperty("MEAN_MOTION_DDOT") BigDecimal meanMotionDdot,
        @JsonProperty("ECCENTRICITY") BigDecimal eccentricity,
        @JsonProperty("INCLINATION") BigDecimal inclination,
        @JsonProperty("RA_OF_ASC_NODE") BigDecimal raan,
        @JsonProperty("ARG_OF_PERICENTER") BigDecimal argPerigee,
        @JsonProperty("MEAN_ANOMALY") BigDecimal meanAnomaly,
        @JsonProperty("EPHEMERIS_TYPE") Integer ephemerisType,
        @JsonProperty("BSTAR") BigDecimal bstar,
        @JsonProperty("RCS_SIZE") String rcsSize,
        @JsonProperty("ELEMENT_SET_NO") Integer elementSetNo,
        @JsonProperty("REV_AT_EPOCH") Integer revAtEpoch,
        @JsonProperty("SEMIMAJOR_AXIS") Double semiMajorAxis,
        @JsonProperty("PERIOD") Double period,
        @JsonProperty("APOAPSIS") Double apoapsis,
        @JsonProperty("PERIAPSIS") Double periapsis,
        @JsonProperty("FILE") Long file,
        @JsonProperty("GP_ID") Integer gpId
) {
    /**
     * Validates that this record has valid and minimum required fields.
     */
    public boolean isValid(LocalDateTime epochCutoff) {
        return noradCatId != null
                && tleLine1 != null && !tleLine1.isBlank()
                && tleLine2 != null && !tleLine2.isBlank()
                && epoch != null && epoch.isAfter(epochCutoff)
                && meanMotion != null && meanMotion.compareTo(BigDecimal.ZERO) > 0
                && eccentricity != null && eccentricity.compareTo(BigDecimal.ZERO) >= 0
                && eccentricity.compareTo(new BigDecimal("0.95")) < 0;
    }

    /**
     * Get the epoch as OffsetDateTime in UTC.
     */
    public OffsetDateTime getEpochUtc() {
        return epoch != null ? epoch.atOffset(ZoneOffset.UTC) : null;
    }
}