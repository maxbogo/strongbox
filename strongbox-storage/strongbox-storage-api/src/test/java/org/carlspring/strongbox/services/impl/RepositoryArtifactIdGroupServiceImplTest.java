package org.carlspring.strongbox.services.impl;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import javax.inject.Inject;

import org.carlspring.strongbox.StorageApiTestConfig;
import org.carlspring.strongbox.data.CacheManagerTestExecutionListener;
import org.carlspring.strongbox.domain.ArtifactIdGroupEntity;
import org.carlspring.strongbox.repositories.ArtifactIdGroupRepository;
import org.janusgraph.core.SchemaViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;

/**
 * @author Przemyslaw Fusik
 */
@SpringBootTest
@ActiveProfiles(profiles = "test")
@ContextConfiguration(classes = StorageApiTestConfig.class)
@TestExecutionListeners(listeners = { CacheManagerTestExecutionListener.class },
                        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RepositoryArtifactIdGroupServiceImplTest
{

    @Inject
    private ArtifactIdGroupRepository artifactIdGroupRepository;

    @Test
    public void repositoryArtifactIdGroupShouldBeProtectedByIndex()
    {
        ArtifactIdGroupEntity g1 = new ArtifactIdGroupEntity();
        g1.setName("a1");
        g1.setRepositoryId("r1");
        g1.setStorageId("s1");
        System.out.println(artifactIdGroupRepository.save(g1).getUuid());

        assertThatExceptionOfType(SchemaViolationException.class)
                .isThrownBy(() -> {
                    ArtifactIdGroupEntity g2 = new ArtifactIdGroupEntity();
                    g2.setName("a1");
                    g2.setRepositoryId("r1");
                    g2.setStorageId("s1");
                    System.out.println(artifactIdGroupRepository.save(g2).getUuid());
        });
    }
}
