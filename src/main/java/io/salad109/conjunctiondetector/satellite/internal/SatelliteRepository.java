package io.salad109.conjunctiondetector.satellite.internal;

import io.salad109.conjunctiondetector.satellite.Satellite;
import io.salad109.conjunctiondetector.satellite.SatelliteBriefInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteDetails;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService.NameTokenCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SatelliteRepository extends JpaRepository<Satellite, Integer> {

    @Override
    long count();

    long countByObjectType(String objectType);

    @Query(value = """
            WITH tokens AS (
                SELECT regexp_split_to_table(upper(trim(object_name)), '[^A-Z0-9]+') AS fragment
                FROM satellite
                WHERE object_name IS NOT NULL
            )
            SELECT fragment, COUNT(*) AS count
            FROM tokens
            WHERE length(fragment) >= 3
              AND fragment ~ '[A-Z]'
              AND fragment NOT IN ('DEB', 'OBJECT', 'ASSIGNED', 'TBA')
            GROUP BY fragment
            ORDER BY count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<NameTokenCount> findTopNameTokens(int limit);

    @Query("SELECT COUNT(s) FROM Satellite s WHERE s.apogeeKm <= 2000")
    long countLeo();

    @Query("SELECT COUNT(s) FROM Satellite s " +
            "WHERE s.apogeeKm BETWEEN 35000 AND 37000 AND s.perigeeKm > 2000")
    long countGeo();

    @Query("SELECT COUNT(s) FROM Satellite s " +
            "WHERE s.apogeeKm > 2000 AND s.apogeeKm < 35000 AND s.perigeeKm > 2000")
    long countMeo();

    int deleteSatellitesByNoradCatIdNotIn(Collection<Integer> id);

    Optional<SatelliteDetails> findSatelliteDetailsByNoradCatId(int noradCatId);

    @Query("SELECT new io.salad109.conjunctiondetector.satellite.SatelliteScanInfo(" +
            "s.noradCatId, s.tleLine1, s.tleLine2, s.epoch, s.perigeeKm, s.objectType) " +
            "FROM Satellite s")
    List<SatelliteScanInfo> findAllSatelliteScanInfo();

    @Query("SELECT new io.salad109.conjunctiondetector.satellite.SatelliteBriefInfo(" +
            "s.noradCatId, s.objectName, s.objectType, s.countryCode, s.perigeeKm, s.apogeeKm, " +
            "s.inclination, s.eccentricity, s.period, s.conjunctionCount) " +
            "FROM Satellite s")
    Page<SatelliteBriefInfo> getSatelliteBriefInfos(Pageable pageable);

    @SuppressWarnings("SqlWithoutWhere")
    @Modifying
    @Query(value = """
            UPDATE satellite s SET conjunction_count = (
                SELECT COUNT(*) FROM conjunction c
                WHERE c.object1_norad_id = s.norad_cat_id
                   OR c.object2_norad_id = s.norad_cat_id
            )
            """, nativeQuery = true)
    void updateConjunctionCounts();
}
