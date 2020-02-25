package org.carlspring.strongbox.storage.indexing.local;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.data.service.support.search.PagingCriteria;
import org.carlspring.strongbox.domain.ArtifactEntity;
import org.carlspring.strongbox.domain.ArtifactIdGroupEntity;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.services.ArtifactIdGroupService;
import org.carlspring.strongbox.storage.indexing.*;
import org.carlspring.strongbox.storage.indexing.RepositoryIndexDirectoryPathResolver.RepositoryIndexDirectoryPathResolverQualifier;
import org.carlspring.strongbox.storage.indexing.RepositoryIndexCreator.RepositoryIndexCreatorQualifier;
import org.carlspring.strongbox.storage.indexing.RepositoryIndexingContextFactory.RepositoryIndexingContextFactoryQualifier;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.repository.RepositoryTypeEnum;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import org.apache.maven.index.ArtifactContext;
import org.springframework.stereotype.Component;

/**
 * @author Przemyslaw Fusik
 */
@Component
@RepositoryIndexCreatorQualifier(RepositoryTypeEnum.HOSTED)
public class RepositoryHostedIndexCreator
        extends AbstractRepositoryIndexCreator
{

    private static final int REPOSITORY_ARTIFACT_GROUP_FETCH_PAGE_SIZE = 100;

    @Inject
    private ArtifactIdGroupService repositoryArtifactIdGroupService;

    @Inject
    @RepositoryIndexDirectoryPathResolverQualifier(IndexTypeEnum.LOCAL)
    private RepositoryIndexDirectoryPathResolver indexDirectoryPathResolver;

    @Inject
    @RepositoryIndexingContextFactoryQualifier(IndexTypeEnum.LOCAL)
    private RepositoryIndexingContextFactory indexingContextFactory;

    @Override
    protected void onIndexingContextCreated(final RepositoryPath repositoryIndexDirectoryPath,
                                            final RepositoryCloseableIndexingContext indexingContext)
            throws IOException
    {
        indexingContext.purge();
        fulfillIndexingContext(indexingContext);
        IndexPacker.pack(repositoryIndexDirectoryPath, indexingContext);
    }

    @Override
    protected RepositoryIndexingContextFactory getRepositoryIndexingContextFactory()
    {
        return indexingContextFactory;
    }

    @Override
    protected RepositoryIndexDirectoryPathResolver getRepositoryIndexDirectoryPathResolver()
    {
        return indexDirectoryPathResolver;
    }

    private void fulfillIndexingContext(final RepositoryCloseableIndexingContext indexingContext)
            throws IOException
    {

        final Repository repository = indexingContext.getRepositoryRaw();
        final String storageId = repository.getStorage().getId();
        final String repositoryId = repository.getId();

        final long totalArtifactGroupsInRepository = repositoryArtifactIdGroupService.count(storageId,
                                                                                            repositoryId);
        if (totalArtifactGroupsInRepository == 0)
        {
            return;
        }

        final long iterations = totalArtifactGroupsInRepository / REPOSITORY_ARTIFACT_GROUP_FETCH_PAGE_SIZE + 1;

        for (int i = 0; i < iterations; i++)
        {
            final PagingCriteria pagingCriteria = new PagingCriteria(i * REPOSITORY_ARTIFACT_GROUP_FETCH_PAGE_SIZE,
                                                                     REPOSITORY_ARTIFACT_GROUP_FETCH_PAGE_SIZE);
            final List<ArtifactIdGroupEntity> repositoryArtifactIdGroupEntries = repositoryArtifactIdGroupService.findMatching(
                    storageId,
                    repositoryId,
                    pagingCriteria);

            final List<ArtifactContext> artifactContexts = createArtifactContexts(repositoryArtifactIdGroupEntries);
            Indexer.INSTANCE.addArtifactsToIndex(artifactContexts, indexingContext);
        }
    }

    private List<ArtifactContext> createArtifactContexts(final List<ArtifactIdGroupEntity> repositoryArtifactIdGroupEntries)
    {
        final List<ArtifactContext> artifactContexts = new ArrayList<>();
        for (final ArtifactIdGroupEntity repositoryArtifactIdGroupEntry : repositoryArtifactIdGroupEntries)
        {
            final Map<String, List<ArtifactEntity>> groupedByVersion = groupArtifactEntriesByVersion(
                    repositoryArtifactIdGroupEntry);
            for (final Map.Entry<String, List<ArtifactEntity>> sameVersionArtifactEntries : groupedByVersion.entrySet())
            {
                for (final ArtifactEntity artifactEntry : sameVersionArtifactEntries.getValue())
                {
                    if (!isIndexable(artifactEntry))
                    {
                        continue;
                    }

                    final List<ArtifactEntity> groupClone = new ArrayList<>(sameVersionArtifactEntries.getValue());
                    groupClone.remove(artifactEntry);

                    final ArtifactEntryArtifactContextHelper artifactContextHelper = createArtifactContextHelper(
                            artifactEntry,
                            groupClone);
                    final ArtifactEntryArtifactContext ac = new ArtifactEntryArtifactContext(artifactEntry,
                                                                                             artifactContextHelper);
                    artifactContexts.add(ac);
                }
            }
        }
        return artifactContexts;
    }

    private Map<String, List<ArtifactEntity>> groupArtifactEntriesByVersion(final ArtifactIdGroupEntity groupEntry)
    {
        final Map<String, List<ArtifactEntity>> groupedByVersion = new LinkedHashMap<>();
        for (final ArtifactEntity artifactEntry : groupEntry.getArtifacts())
        {
            final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
            final String version = coordinates.getVersion();
            List<ArtifactEntity> artifactEntries = groupedByVersion.get(version);
            if (artifactEntries == null)
            {
                artifactEntries = new ArrayList<>();
                groupedByVersion.put(version, artifactEntries);
            }
            artifactEntries.add(artifactEntry);
        }
        return groupedByVersion;
    }

    private ArtifactEntryArtifactContextHelper createArtifactContextHelper(final ArtifactEntity artifactEntry,
                                                                           final List<ArtifactEntity> group)
    {
        boolean pomExists = false;
        boolean sourcesExists = false;
        boolean javadocExists = false;
        if (group.size() < 1)
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }
        final MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        if ("javadoc".equals(coordinates.getClassifier()) || "sources".equals(coordinates.getClassifier()))
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }
        if ("pom".equals(coordinates.getExtension()))
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }

        for (final ArtifactEntity neighbour : group)
        {
            final MavenArtifactCoordinates neighbourCoordinates = (MavenArtifactCoordinates) neighbour.getArtifactCoordinates();
            pomExists |=
                    ("pom".equals(neighbourCoordinates.getExtension()) &&
                     neighbourCoordinates.getClassifier() == null);
            if (Objects.equals(coordinates.getExtension(), neighbourCoordinates.getExtension()))
            {
                javadocExists |= "javadoc".equals(neighbourCoordinates.getClassifier());
                sourcesExists |= "sources".equals(neighbourCoordinates.getClassifier());
            }
        }
        return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
    }

    /**
     * org.apache.maven.index.DefaultArtifactContextProducer#isIndexable(java.io.File)
     */
    private boolean isIndexable(final ArtifactEntity artifactEntry)
    {
        final String filename = Paths.get(artifactEntry.getArtifactPath()).getFileName().toString();

        if (filename.equals("maven-metadata.xml")
            // || filename.endsWith( "-javadoc.jar" )
            // || filename.endsWith( "-javadocs.jar" )
            // || filename.endsWith( "-sources.jar" )
            || filename.endsWith(".properties")
            // || filename.endsWith( ".xml" ) // NEXUS-3029
            || filename.endsWith(".asc") || filename.endsWith(".md5") || filename.endsWith(".sha1"))
        {
            return false;
        }

        return true;
    }
}
