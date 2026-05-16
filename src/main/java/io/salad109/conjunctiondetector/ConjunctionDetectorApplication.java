package io.salad109.conjunctiondetector;

import jakarta.annotation.PostConstruct;
import org.orekit.data.ClasspathCrawler;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.time.Clock;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class ConjunctionDetectorApplication {

    static void main(String[] args) {
        SpringApplication.run(ConjunctionDetectorApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @PostConstruct
    public void initOrekit() {
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();

        File devPath = new File("src/main/resources/orekit-data");
        if (devPath.exists()) {
            manager.addProvider(new DirectoryCrawler(devPath));
        } else {
            manager.addProvider(new ClasspathCrawler("orekit-data/tai-utc.dat"));
        }
    }
}
