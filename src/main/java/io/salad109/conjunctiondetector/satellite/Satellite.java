package io.salad109.conjunctiondetector.satellite;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;

@Setter
@Getter
@Entity
@Table(name = "satellite")
public class Satellite implements Persistable<Integer> {

    @Id
    @Column(name = "norad_cat_id")
    private Integer noradCatId;

    @Column(name = "object_name")
    private String objectName;

    @Column(name = "object_id")
    private String objectId;

    @Column(name = "object_type")
    private String objectType;

    @Column(name = "classification_type")
    private String classificationType;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "launch_date")
    private LocalDate launchDate;

    @Column(name = "site")
    private String site;

    @Column(name = "decay_date")
    private LocalDate decayDate;

    @Column(name = "epoch")
    private OffsetDateTime epoch;

    @Column(name = "creation_date")
    private LocalDateTime creationDate;

    @Column(name = "tle_line0")
    private String tleLine0;

    @Column(name = "tle_line1")
    private String tleLine1;

    @Column(name = "tle_line2")
    private String tleLine2;

    // Orbital elements
    @Column(name = "mean_motion")
    private BigDecimal meanMotion;

    @Column(name = "mean_motion_dot")
    private BigDecimal meanMotionDot;

    @Column(name = "mean_motion_ddot")
    private BigDecimal meanMotionDdot;

    @Column(name = "eccentricity")
    private BigDecimal eccentricity;

    @Column(name = "inclination")
    private BigDecimal inclination;

    @Column(name = "raan")
    private BigDecimal raan;

    @Column(name = "arg_perigee")
    private BigDecimal argPerigee;

    @Column(name = "mean_anomaly")
    private BigDecimal meanAnomaly;

    @Column(name = "ephemeris_type")
    private Integer ephemerisType;

    @Column(name = "bstar")
    private BigDecimal bstar;

    @Column(name = "rcs_size")
    private String rcsSize;

    @Column(name = "element_set_no")
    private Integer elementSetNo;

    @Column(name = "rev_at_epoch")
    private Integer revAtEpoch;

    @Column(name = "semi_major_axis_km")
    private Double semiMajorAxisKm;

    @Column(name = "period")
    private Double period;

    @Column(name = "perigee_km")
    private Double perigeeKm;

    @Column(name = "apogee_km")
    private Double apogeeKm;

    @Column(name = "file_number")
    private Long fileNumber;

    @Column(name = "gp_id")
    private Integer gpId;

    @Column(name = "conjunction_count")
    private int conjunctionCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Satellite() {
    }

    public Satellite(Integer noradCatId) {
        this.noradCatId = noradCatId;
    }

    @Override
    public Integer getId() {
        return noradCatId;
    }

    // NORAD key is never null, so a null @Version signal "new" instead.
    // Lets save() insert directly rather than SELECT first.
    @Override
    public boolean isNew() {
        return version == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Satellite satellite)) return false;
        return Objects.equals(noradCatId, satellite.noradCatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(noradCatId);
    }
}
