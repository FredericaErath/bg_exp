mgmt = graph.openManagement()

mgmt.getGraphIndexes(Vertex.class).each { index ->
    mgmt.updateIndex(index, SchemaAction.DISABLE_INDEX).get()
    mgmt.updateIndex(index, SchemaAction.REMOVE_INDEX).get()
}
mgmt.getGraphIndexes(Edge.class).each { index ->
    mgmt.updateIndex(index, SchemaAction.DISABLE_INDEX).get()
    mgmt.updateIndex(index, SchemaAction.REMOVE_INDEX).get()
}

useridKey = mgmt.getPropertyKey("userid")
mgmt.buildIndex("userid_unique_index", Vertex.class)
        .addKey(useridKey)
        .unique()
        .buildCompositeIndex()

mgmt.commit()

graph.tx().rollback()
ManagementSystem.awaitGraphIndexStatus(graph, "userid_unique_index").call()
mgmt = graph.openManagement()
mgmt.updateIndex(mgmt.getGraphIndex("userid_unique_index"), SchemaAction.ENABLE_INDEX)
mgmt.commit()
