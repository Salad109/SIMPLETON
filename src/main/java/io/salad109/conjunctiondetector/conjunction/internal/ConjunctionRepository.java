package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionInfo;
import io.salad109.conjunctiondetector.conjunction.VisualizationData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConjunctionRepository extends JpaRepository<Conjunction, Long> {

    @Modifying
    @Query(value = "TRUNCATE TABLE conjunction", nativeQuery = true)
    void truncate();

    @Query("SELECT COUNT(c) FROM Conjunction c WHERE c.relativeVelocityMS > 10")
    long countActive();

    @Query("SELECT COUNT(c) FROM Conjunction c WHERE c.collisionProbability >= 1e-6")
    long countHighRisk();

    @Query("SELECT new io.salad109.conjunctiondetector.conjunction.ConjunctionInfo(" +
            "c.id, c.missDistanceKm, c.tca, c.relativeVelocityMS, c.collisionProbability, " +
            "c.object1NoradId, s1.objectName, s1.objectType, " +
            "c.object2NoradId, s2.objectName, s2.objectType) " +
            "FROM Conjunction c " +
            "JOIN Satellite s1 ON c.object1NoradId = s1.noradCatId " +
            "JOIN Satellite s2 ON c.object2NoradId = s2.noradCatId " +
            "WHERE c.relativeVelocityMS > 10")
    Page<ConjunctionInfo> getConjunctionInfos(Pageable pageable);

    @Query("SELECT new io.salad109.conjunctiondetector.conjunction.ConjunctionInfo(" +
            "c.id, c.missDistanceKm, c.tca, c.relativeVelocityMS, c.collisionProbability, " +
            "c.object1NoradId, s1.objectName, s1.objectType, " +
            "c.object2NoradId, s2.objectName, s2.objectType) " +
            "FROM Conjunction c " +
            "JOIN Satellite s1 ON c.object1NoradId = s1.noradCatId " +
            "JOIN Satellite s2 ON c.object2NoradId = s2.noradCatId")
    Page<ConjunctionInfo> getConjunctionInfosWithFormations(Pageable pageable);

    @Query("SELECT new io.salad109.conjunctiondetector.conjunction.ConjunctionInfo(" +
            "c.id, c.missDistanceKm, c.tca, c.relativeVelocityMS, c.collisionProbability, " +
            "c.object1NoradId, s1.objectName, s1.objectType, " +
            "c.object2NoradId, s2.objectName, s2.objectType) " +
            "FROM Conjunction c " +
            "JOIN Satellite s1 ON c.object1NoradId = s1.noradCatId " +
            "JOIN Satellite s2 ON c.object2NoradId = s2.noradCatId " +
            "WHERE c.object1NoradId = :noradId OR c.object2NoradId = :noradId")
    List<ConjunctionInfo> getConjunctionInfosByNoradId(int noradId);

    @Query("SELECT new io.salad109.conjunctiondetector.conjunction.VisualizationData(" +
            "c.id, c.missDistanceKm, c.tca, c.relativeVelocityMS, c.collisionProbability, " +
            "c.object1NoradId, s1.objectName, s1.objectType, s1.tleLine1, s1.tleLine2, " +
            "c.object2NoradId, s2.objectName, s2.objectType, s2.tleLine1, s2.tleLine2) " +
            "FROM Conjunction c " +
            "JOIN Satellite s1 ON c.object1NoradId = s1.noradCatId " +
            "JOIN Satellite s2 ON c.object2NoradId = s2.noradCatId " +
            "WHERE c.id = :id")
    Optional<VisualizationData> getVisualizationData(Long id);
}
