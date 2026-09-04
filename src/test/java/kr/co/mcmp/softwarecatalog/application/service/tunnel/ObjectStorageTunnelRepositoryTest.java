package kr.co.mcmp.softwarecatalog.application.service.tunnel;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import kr.co.mcmp.softwarecatalog.application.model.ObjectStorageTunnel;
import kr.co.mcmp.softwarecatalog.application.repository.ObjectStorageTunnelRepository;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=ObjectStorageTunnelRepositoryTest.Config.class)
class ObjectStorageTunnelRepositoryTest {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=ObjectStorageTunnelRepository.class,
        excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern=".*(?<!ObjectStorageTunnel)Repository"))
    static class Config {
        @Bean DataSource dataSource(){
            return new DriverManagerDataSource("jdbc:h2:mem:tunnel;DB_CLOSE_DELAY=-1","sa","");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source){
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(ObjectStorageTunnel.class.getName()));
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto","create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory){
            return new JpaTransactionManager(factory);
        }
    }
    @Autowired ObjectStorageTunnelRepository repository;

    @BeforeEach void prepare(){
        repository.deleteAll();
        var t=new ObjectStorageTunnel();
        t.setId("a".repeat(32));t.setDeploymentId(1L);
        t.setNamespace("default");t.setMciId("infra");t.setVmId("vm");t.setContainerId("b".repeat(64));
        repository.saveAndFlush(t);
    }

    @Test void onlyOneOwnerCanClaimAndExpiredLeaseCanBeRecovered(){
        Instant now=Instant.now();
        assertThat(repository.claim("a".repeat(32),"owner1",now,now.plusSeconds(120))).isEqualTo(1);
        assertThat(repository.claim("a".repeat(32),"owner2",now,now.plusSeconds(120))).isZero();
        assertThat(repository.claim("a".repeat(32),"owner2",now.plusSeconds(121),now.plusSeconds(240))).isEqualTo(1);
        assertThat(repository.findByDeploymentId(1L).orElseThrow().getLeaseOwner()).isEqualTo("owner2");
    }
    @Test void renewCannotResurrectAnExpiredLease(){
        Instant now=Instant.now();
        repository.claim("a".repeat(32),"owner1",now,now.plusSeconds(1));
        assertThat(repository.renew("owner1",now.plusSeconds(2),now.plusSeconds(120))).isZero();
    }
    @Test void desiredStateIsDurableAndMetadataRequiresOwnership(){
        Instant now=Instant.now();
        repository.claim("a".repeat(32),"owner1",now,now.plusSeconds(120));
        repository.identity("a".repeat(32),"wrong","bad","bad");
        assertThat(repository.findById("a".repeat(32)).orElseThrow().getHostKey()).isNull();
        repository.identity("a".repeat(32),"owner1","vm-uid","ssh-ed25519 test");
        repository.desire("a".repeat(32),"DELETE_PENDING",now);
        repository.status("a".repeat(32),"owner1","ERROR",now);
        var saved=repository.findByDeploymentId(1L).orElseThrow();
        assertThat(saved.getHostKey()).isEqualTo("ssh-ed25519 test");
        assertThat(saved.getDesiredState()).isEqualTo("DELETE_PENDING");
        repository.release("owner1");
        assertThat(repository.claim(saved.getId(),"owner2",now,now.plusSeconds(120))).isEqualTo(1);
    }
}
