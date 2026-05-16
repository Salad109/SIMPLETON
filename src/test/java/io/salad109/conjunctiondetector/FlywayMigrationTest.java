package io.salad109.conjunctiondetector;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static Flyway flyway;

    @BeforeAll
    static void migrate() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        flyway.migrate();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void everyMigrationAppliesSuccessfully() {
        MigrationInfo[] all = flyway.info().all();
        assertThat(all).as("Flyway sees the migration scripts").isNotEmpty();
        assertThat(all).allSatisfy(m -> assertThat(m.getState())
                .as("migration %s (%s)", m.getVersion(), m.getDescription())
                .isEqualTo(MigrationState.SUCCESS));
    }

    @Test
    void canonicalNoradOrderingConstraintRejectsReversedPair() throws SQLException {
        try (Connection conn = open();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO satellite (norad_cat_id, object_name) VALUES (1000, 'A'), (2000, 'B')");

            assertThatThrownBy(() -> stmt.execute(
                    "INSERT INTO conjunction (object1_norad_id, object2_norad_id, miss_distance_km, tca) " +
                            "VALUES (2000, 1000, 1.0, NOW())"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("objects_ordered");
        }
    }

    @Test
    void deletingSatelliteCascadesToConjunctions() throws SQLException {
        try (Connection conn = open();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO satellite (norad_cat_id, object_name) VALUES (3000, 'X'), (4000, 'Y')");
            stmt.execute("INSERT INTO conjunction (object1_norad_id, object2_norad_id, miss_distance_km, tca) " +
                    "VALUES (3000, 4000, 1.0, NOW())");

            stmt.execute("DELETE FROM satellite WHERE norad_cat_id = 3000");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM conjunction WHERE object1_norad_id = 3000 OR object2_norad_id = 3000")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("conjunctions referencing the deleted satellite must be cascaded out")
                        .isZero();
            }
        }
    }
}
