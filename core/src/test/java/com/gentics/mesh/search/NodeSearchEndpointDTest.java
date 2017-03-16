package com.gentics.mesh.search;

import static com.gentics.mesh.test.TestDataProvider.PROJECT_NAME;
import static com.gentics.mesh.test.TestSize.FULL;
import static com.gentics.mesh.test.context.MeshTestHelper.call;
import static com.gentics.mesh.test.context.MeshTestHelper.expectResponseMessage;
import static com.gentics.mesh.test.context.MeshTestHelper.getSimpleQuery;
import static com.gentics.mesh.test.context.MeshTestHelper.getSimpleTermQuery;
import static com.gentics.mesh.util.MeshAssert.failingLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import org.codehaus.jettison.json.JSONException;
import org.junit.Test;

import com.gentics.mesh.FieldUtil;
import com.gentics.mesh.core.data.Language;
import com.gentics.mesh.core.data.NodeGraphFieldContainer;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.User;
import com.gentics.mesh.core.data.node.Node;
import com.gentics.mesh.core.data.node.field.nesting.MicronodeGraphField;
import com.gentics.mesh.core.data.relationship.GraphPermission;
import com.gentics.mesh.core.data.schema.SchemaContainerVersion;
import com.gentics.mesh.core.rest.common.GenericMessageResponse;
import com.gentics.mesh.core.rest.node.NodeCreateRequest;
import com.gentics.mesh.core.rest.node.NodeListResponse;
import com.gentics.mesh.core.rest.node.NodeResponse;
import com.gentics.mesh.core.rest.project.ProjectCreateRequest;
import com.gentics.mesh.core.rest.project.ProjectResponse;
import com.gentics.mesh.core.rest.schema.SchemaReference;
import com.gentics.mesh.core.rest.schema.impl.SchemaResponse;
import com.gentics.mesh.core.rest.schema.impl.SchemaUpdateRequest;
import com.gentics.mesh.core.rest.tag.TagResponse;
import com.gentics.mesh.dagger.MeshInternal;
import com.gentics.mesh.graphdb.NoTx;
import com.gentics.mesh.json.JsonUtil;
import com.gentics.mesh.parameter.LinkType;
import com.gentics.mesh.parameter.impl.NodeParametersImpl;
import com.gentics.mesh.parameter.impl.PagingParametersImpl;
import com.gentics.mesh.parameter.impl.PublishParametersImpl;
import com.gentics.mesh.parameter.impl.SchemaUpdateParametersImpl;
import com.gentics.mesh.parameter.impl.VersioningParametersImpl;
import com.gentics.mesh.test.context.MeshTestSetting;
import com.gentics.mesh.test.performance.TestUtils;

import io.vertx.core.json.JsonObject;

@MeshTestSetting(useElasticsearch = true, startServer = true, testSize = FULL)
public class NodeSearchEndpointDTest extends AbstractNodeSearchEndpointTest {

	@Test
	public void testSearchListOfMicronodesResolveLinks() throws Exception {
		try (NoTx noTx = db().noTx()) {
			addMicronodeListField();
			recreateIndices();
		}

		for (String firstName : Arrays.asList("Mickey", "Donald")) {
			for (String lastName : Arrays.asList("Mouse", "Duck")) {
				// valid names always begin with the same character
				boolean expectResult = firstName.substring(0, 1).equals(lastName.substring(0, 1));

				NodeListResponse response = call(() -> client().searchNodes(PROJECT_NAME, getNestedVCardListSearch(firstName, lastName),
						new PagingParametersImpl().setPage(1).setPerPage(2), new NodeParametersImpl().setResolveLinks(LinkType.FULL),
						new VersioningParametersImpl().draft()));

				if (expectResult) {
					assertEquals("Check returned search results", 1, response.getData().size());
					assertEquals("Check total search results", 1, response.getMetainfo().getTotalCount());
					for (NodeResponse nodeResponse : response.getData()) {
						assertNotNull("Returned node must not be null", nodeResponse);
						assertEquals("Check result uuid", db().noTx(() -> content("concorde").getUuid()), nodeResponse.getUuid());
					}
				} else {
					assertEquals("Check returned search results", 0, response.getData().size());
					assertEquals("Check total search results", 0, response.getMetainfo().getTotalCount());
				}
			}
		}
	}

	@Test
	public void testTrigramSearchQuery() throws Exception {
		// 1. Index all existing contents
		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		//TODO this test should work once the lowercase filter has been applied
		JsonObject query = new JsonObject().put("min_score", 1.0).put("query",
				new JsonObject().put("match_phrase", new JsonObject().put("fields.content", new JsonObject().put("query", "Hersteller"))));

		NodeListResponse response = call(() -> client().searchNodes(PROJECT_NAME, query.toString(),
				new PagingParametersImpl().setPage(1).setPerPage(2), new VersioningParametersImpl().draft(), new NodeParametersImpl().setLanguages("de")));
		assertEquals(1, response.getData().size());

		String name = response.getData().get(0).getFields().getStringField("name").getString();
		assertEquals("Honda NR german", name);
	}

	@Test
	public void testSchemaMigrationNodeSearchTest() throws Exception {

		// 1. Index all existing contents
		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		// 2. Assert that the the en, de variant of the node could be found in the search index
		String uuid = db().noTx(() -> content("concorde").getUuid());
		CountDownLatch latch = TestUtils.latchForMigrationCompleted(client());
		NodeListResponse response = call(
				() -> client().searchNodes(PROJECT_NAME, getSimpleTermQuery("uuid", uuid), new PagingParametersImpl().setPage(1).setPerPage(10),
						new NodeParametersImpl().setLanguages("en", "de"), new VersioningParametersImpl().draft()));
		assertEquals("We expect to find the two language versions.", 2, response.getData().size());

		// 3. Prepare an updated schema
		String schemaUuid;
		SchemaUpdateRequest schema;
		try (NoTx noTx = db().noTx()) {
			Node concorde = content("concorde");
			SchemaContainerVersion schemaVersion = concorde.getSchemaContainer().getLatestVersion();
			schema = JsonUtil.readValue(schemaVersion.getJson(), SchemaUpdateRequest.class);
			schema.addField(FieldUtil.createStringFieldSchema("extraField"));
			schemaUuid = concorde.getSchemaContainer().getUuid();
		}
		// Clear the schema storage in order to purge the reference from the storage which we would otherwise modify.
		MeshInternal.get().serverSchemaStorage().clear();

		// 4. Invoke the schema migration
		GenericMessageResponse message = call(
				() -> client().updateSchema(schemaUuid, schema, new SchemaUpdateParametersImpl().setUpdateAssignedReleases(false)));
		expectResponseMessage(message, "migration_invoked", "content");

		// 5. Assign the new schema version to the release
		SchemaResponse updatedSchema = call(() -> client().findSchemaByUuid(schemaUuid));
		call(() -> client().assignReleaseSchemaVersions(PROJECT_NAME, db().noTx(() -> project().getLatestRelease().getUuid()),
				new SchemaReference().setUuid(updatedSchema.getUuid()).setVersion(updatedSchema.getVersion())));

		// Wait for migration to complete
		failingLatch(latch);

		searchProvider().refreshIndex();

		// 6. Assert that the two migrated language variations can be found
		response = call(
				() -> client().searchNodes(PROJECT_NAME, getSimpleTermQuery("uuid", uuid), new PagingParametersImpl().setPage(1).setPerPage(10),
						new NodeParametersImpl().setLanguages("en", "de"), new VersioningParametersImpl().draft()));
		assertEquals("We only expect to find the two language versions while searching for uuid {" + uuid + "}", 2, response.getData().size());
	}

	@Test
	public void testSearchManyNodesWithMicronodes() throws Exception {
		try (NoTx noTx = db().noTx()) {
			String releaseUuid = project().getLatestRelease().getUuid();
			int numAdditionalNodes = 99;
			addMicronodeField();
			User user = user();
			Language english = english();
			Node concorde = content("concorde");

			Project project = concorde.getProject();
			Node parentNode = concorde.getParentNode(releaseUuid);
			SchemaContainerVersion schemaVersion = concorde.getSchemaContainer().getLatestVersion();

			for (int i = 0; i < numAdditionalNodes; i++) {
				Node node = parentNode.create(user, schemaVersion, project);
				NodeGraphFieldContainer fieldContainer = node.createGraphFieldContainer(english, node.getProject().getLatestRelease(), user);
				fieldContainer.createString("name").setString("Name_" + i);
				MicronodeGraphField vcardField = fieldContainer.createMicronode("vcard", microschemaContainers().get("vcard").getLatestVersion());
				vcardField.getMicronode().createString("firstName").setString("Mickey");
				vcardField.getMicronode().createString("lastName").setString("Mouse");
				role().grantPermissions(node, GraphPermission.READ_PERM);
			}
			MeshInternal.get().boot().meshRoot().getNodeRoot().reload();
			recreateIndices();

			NodeListResponse response = call(() -> client().searchNodes(PROJECT_NAME, getSimpleQuery("Mickey"),
					new PagingParametersImpl().setPage(1).setPerPage(numAdditionalNodes + 1), new VersioningParametersImpl().draft()));

			assertEquals("Check returned search results", numAdditionalNodes + 1, response.getData().size());
		}
	}

	/**
	 * Tests if all tags are in the node response when searching for a node.
	 * 
	 * @throws JSONException
	 * @throws InterruptedException
	 */
	@Test
	public void testTagCount() throws Exception {
		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		try (NoTx noTx = db().noTx()) {
			Node node = content("concorde");
			int previousTagCount = node.getTags(project().getLatestRelease()).size();
			// Create tags:
			int tagCount = 20;
			for (int i = 0; i < tagCount; i++) {
				TagResponse tagResponse = createTag(PROJECT_NAME, tagFamily("colors").getUuid(), "tag" + i);
				// Add tags to node:
				call(() -> client().addTagToNode(PROJECT_NAME, node.getUuid(), tagResponse.getUuid(), new VersioningParametersImpl().draft()));
			}

			NodeListResponse response = call(
					() -> client().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"), new VersioningParametersImpl().draft()));
			assertEquals("Expect to only get one search result", 1, response.getMetainfo().getTotalCount());

			// assert tag count
			long nColorTags = response.getData().get(0).getTags().stream().filter(ref -> ref.getTagFamily().equals("colors")).count();
			long nBasicTags = response.getData().get(0).getTags().stream().filter(ref -> ref.getTagFamily().equals("basic")).count();
			assertEquals("Expect correct tag count", previousTagCount + tagCount, nColorTags + nBasicTags);
		}
	}

	@Test
	public void testGlobalNodeSearch() throws Exception {
		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		try (NoTx noTx = db().noTx()) {
			NodeResponse oldNode = call(
					() -> client().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(), new VersioningParametersImpl().draft()));

			ProjectCreateRequest createProject = new ProjectCreateRequest();
			createProject.setSchema(new SchemaReference().setName("folder"));
			createProject.setName("mynewproject");
			ProjectResponse projectResponse = call(() -> client().createProject(createProject));

			NodeCreateRequest createNode = new NodeCreateRequest();
			createNode.setLanguage("en");
			createNode.setSchema(new SchemaReference().setName("folder"));
			createNode.setParentNode(projectResponse.getRootNode());
			createNode.getFields().put("name", FieldUtil.createStringField("Concorde"));
			NodeResponse newNode = call(() -> client().createNode("mynewproject", createNode));

			// search in old project
			NodeListResponse response = call(
					() -> client().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"), new VersioningParametersImpl().draft()));
			assertThat(response.getData()).as("Search result in " + PROJECT_NAME).usingElementComparatorOnFields("uuid").containsOnly(oldNode);

			// search in new project
			response = call(() -> client().searchNodes("mynewproject", getSimpleQuery("Concorde"), new VersioningParametersImpl().draft()));
			assertThat(response.getData()).as("Search result in mynewproject").usingElementComparatorOnFields("uuid").containsOnly(newNode);

			// search globally
			response = call(() -> client().searchNodes(getSimpleQuery("Concorde"), new VersioningParametersImpl().draft()));
			assertThat(response.getData()).as("Global search result").usingElementComparatorOnFields("uuid").containsOnly(newNode, oldNode);
		}
	}

	@Test
	public void testTakeDraftOffline() throws Exception {

		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		// 1. Create a new project and a folder schema
		ProjectCreateRequest createProject = new ProjectCreateRequest();
		createProject.setName("mynewproject");
		createProject.setSchema(new SchemaReference().setName("folder"));
		ProjectResponse projectResponse = call(() -> client().createProject(createProject));

		// 2. Create a new node in the base of the project
		NodeCreateRequest createNode = new NodeCreateRequest();
		createNode.setLanguage("en");
		createNode.setSchema(new SchemaReference().setName("folder"));
		createNode.setParentNode(projectResponse.getRootNode());
		createNode.getFields().put("name", FieldUtil.createStringField("AwesomeString"));
		NodeResponse newNode = call(() -> client().createNode("mynewproject", createNode));

		// 3. Search globally for published version - The created node is still a draft and thus can't be found
		NodeListResponse response = call(
				() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("published")));
		assertThat(response.getData()).as("Global search result before publishing").isEmpty();

		// 4. Search globally for draft version - The created node should be found since it is a draft
		response = call(() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("draft")));
		assertThat(response.getData()).as("Global search result after publishing").usingElementComparatorOnFields("uuid").containsOnly(newNode);

		// 5. Invoke the take offline action on the project base node
		String baseUuid = db().noTx(() -> project().getBaseNode().getUuid());
		call(() -> client().takeNodeOffline(PROJECT_NAME, baseUuid, new PublishParametersImpl().setRecursive(true)));

		// 6. The node should still be found because it is still a draft
		response = call(() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("draft")));
		assertThat(response.getData()).as("Global search result after publishing").usingElementComparatorOnFields("uuid").containsOnly(newNode);

		// 7. Search globally for the published version - Still there is no published version of the node
		response = call(() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("published")));
		assertThat(response.getData()).as("Global search result before publishing").isEmpty();

	}

	@Test
	public void testGlobalPublishedNodeSearch() throws Exception {
		try (NoTx noTx = db().noTx()) {
			recreateIndices();
		}

		// 1. Create a new project and a folder schema
		ProjectCreateRequest createProject = new ProjectCreateRequest();
		createProject.setName("mynewproject");
		createProject.setSchema(new SchemaReference().setName("folder"));
		ProjectResponse projectResponse = call(() -> client().createProject(createProject));

		// 2. Create a new node in the base of the project
		NodeCreateRequest createNode = new NodeCreateRequest();
		createNode.setLanguage("en");
		createNode.setSchema(new SchemaReference().setName("folder"));
		createNode.setParentNodeUuid(projectResponse.getRootNode().getUuid());
		createNode.getFields().put("name", FieldUtil.createStringField("AwesomeString"));
		NodeResponse newNode = call(() -> client().createNode("mynewproject", createNode));

		// 3. search globally for published version - The created node is still a draft and thus can't be found
		NodeListResponse response = call(
				() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("published")));
		assertThat(response.getData()).as("Global search result before publishing").isEmpty();

		// 4. now publish the node
		call(() -> client().publishNode("mynewproject", newNode.getUuid()));

		// 5. search globally for published version - by default published nodes will be searched for
		response = call(() -> client().searchNodes(getSimpleQuery("AwesomeString"), new VersioningParametersImpl().setVersion("published")));
		assertThat(response.getData()).as("Global search result after publishing").usingElementComparatorOnFields("uuid").containsOnly(newNode);

		// 6. Invoke the take offline action on the project base node
		String baseUuid = db().noTx(() -> project().getBaseNode().getUuid());
		call(() -> client().takeNodeOffline(PROJECT_NAME, baseUuid, new PublishParametersImpl().setRecursive(true)));

		// 7. search globally for published version and assert that the node could be found
		response = call(() -> client().searchNodes(getSimpleQuery("AwesomeString")));
		assertThat(response.getData()).as("Global search result after publishing").usingElementComparatorOnFields("uuid").containsOnly(newNode);
	}

}
