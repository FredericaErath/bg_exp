mgmt = graph.openManagement()

// 创建唯一索引
useridKey = mgmt.getPropertyKey("userid")
if (useridKey == null) {
    useridKey = mgmt.makePropertyKey("userid").dataType(String.class).make()
}

mgmt.buildIndex("userid_unique_index", Vertex.class)
        .addKey(useridKey)
        .unique()
        .buildCompositeIndex()

mgmt.commit()

// 等待索引状态变为 ENABLED
graph.tx().rollback()
ManagementSystem.awaitGraphIndexStatus(graph, "userid_unique_index").call()

// 启用索引
mgmt = graph.openManagement()
mgmt.updateIndex(mgmt.getGraphIndex("userid_unique_index"), SchemaAction.ENABLE_INDEX)
mgmt.commit()
