/*
 * Copyright 2020 Anton Shuvaev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch4idea.ui.explorer

import com.intellij.ide.CommonActionsManager
import com.intellij.ide.TreeExpander
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler.TreeMouseListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import org.elasticsearch4idea.model.ElasticsearchCluster
import org.elasticsearch4idea.model.ElasticsearchIndex
import org.elasticsearch4idea.model.Method
import org.elasticsearch4idea.model.Request
import org.elasticsearch4idea.service.ElasticsearchManager
import org.elasticsearch4idea.ui.ElasticsearchClustersListenerImpl
import org.elasticsearch4idea.ui.editor.ElasticsearchFile
import org.elasticsearch4idea.ui.editor.ElasticsearchFileSystem
import org.elasticsearch4idea.ui.explorer.actions.*
import org.elasticsearch4idea.ui.explorer.table.ElasticsearchInfosTable
import org.elasticsearch4idea.ui.explorer.table.TableEntry
import org.elasticsearch4idea.ui.explorer.tree.ClusterNodeDescriptor
import org.elasticsearch4idea.ui.explorer.tree.ElasticsearchExplorerTreeStructure
import org.elasticsearch4idea.ui.explorer.tree.IndexNodeDescriptor
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel


class ElasticsearchExplorer(
    private val project: Project
) : SimpleToolWindowPanel(true, true), Disposable {

    private val tree: Tree
    private val table: ElasticsearchInfosTable
    private val elasticsearchManager = project.service<ElasticsearchManager>()

    init {
        toolbar = createToolbarPanel()

        table = ElasticsearchInfosTable()
        val infosPanel = JPanel(BorderLayout())
        infosPanel.add(ScrollPaneFactory.createScrollPane(table))

        val treePanel = JPanel(BorderLayout())
        tree = createTree()
        createTreePopupActions()

        treePanel.add(ScrollPaneFactory.createScrollPane(tree))

        val splitter = Splitter(true, 0.6f)
        splitter.firstComponent = treePanel
        splitter.secondComponent = infosPanel

        setContent(splitter)

        ProgressManager.getInstance()
            .run(object : Backgroundable(project, "Getting Elasticsearch cluster info", false) {
                override fun run(indicator: ProgressIndicator) {
                    elasticsearchManager.fetchAllClusters()
                }
            })
    }

    private fun createTree(): Tree {
        val treeStructure = ElasticsearchExplorerTreeStructure(project)

        val treeModel: StructureTreeModel<*> = StructureTreeModel<AbstractTreeStructure>(treeStructure, this)
        val tree = Tree(AsyncTreeModel(treeModel, this))
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.clear()
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

        val listener = ElasticsearchClustersListenerImpl(this, treeModel)
        elasticsearchManager.addClustersListener(listener)
        Disposer.register(this, Disposable { elasticsearchManager.removeClustersListener(listener) })

        TreeUtil.installActions(tree)
        TreeSpeedSearch(tree)

        object : TreeMouseListener(tree, null) {
            override fun processDoubleClick(
                e: MouseEvent,
                dataContext: DataContext,
                treePath: TreePath
            ) {
                openQueryEditorForIndex()
            }
        }.installOn(tree)

        tree.registerKeyboardAction(object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                openQueryEditorForIndex()
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)

        ToolTipManager.sharedInstance().registerComponent(tree)

        tree.selectionModel.addTreeSelectionListener {
            updateNodeInfo()
        }
        return tree
    }

    fun updateNodeInfoIfSelected(elasticsearchCluster: ElasticsearchCluster) {
        if (getSelectedCluster() == elasticsearchCluster
            || getSelectedIndex()?.cluster == elasticsearchCluster
        ) {
            updateNodeInfo()
        }
    }

    fun updateNodeInfo() {
        val cluster = this.getSelectedCluster()
        val index = this.getSelectedIndex()
        ProgressManager.getInstance().run {
            var infos: List<TableEntry>? = null
            if (cluster != null) {
                infos = elasticsearchManager.getClusterInfo(cluster)?.toTableEntryList()
            } else {
                if (index != null) {
                    infos = elasticsearchManager.getIndexInfo(index)?.toTableEntryList()
                }
            }
            if (infos != null) {
                ApplicationManager.getApplication().invokeLater {
                    table.updateInfos(infos)
                }
            }
        }
    }

    private fun createTreePopupActions() {
        val actionPopupGroup = DefaultActionGroup("ElasticsearchExplorerPopupGroup", true)
        val requestIndexInfoProvider = { Request("/${getSelectedIndex()?.name}", "{}", Method.GET) }
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestIndexInfoProvider,
                false,
                "Index Info"
            )
        )
        val requestIndexStatsProvider = { Request("/${getSelectedIndex()?.name}/_stats", "{}", Method.GET) }
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestIndexStatsProvider,
                false,
                "Index Stats"
            )
        )
        actionPopupGroup.addSeparator()
        actionPopupGroup.add(CreateIndexAction(this))
        actionPopupGroup.add(CreateAliasAction(this))
        actionPopupGroup.add(RefreshIndexAction(this))
        actionPopupGroup.add(FlushIndexAction(this))
        actionPopupGroup.add(ForceMergeIndexAction(this))
        actionPopupGroup.addSeparator()
        val requestInfo = Request("/", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestInfo,
                true,
                "Info"
            )
        )
        val requestStats = Request("/_stats", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestStats,
                true,
                "Indices Stats"
            )
        )
        val requestNodesStats = Request("/_nodes/stats", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestNodesStats,
                true,
                "Nodes Stats"
            )
        )
        val requestNodesInfo = Request("/_nodes", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestNodesInfo,
                true,
                "Nodes Info"
            )
        )
        val requestPlugins = Request("/_nodes/plugins", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestPlugins,
                true,
                "Plugins"
            )
        )
        val requestClusterState = Request("/_cluster/state", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestClusterState,
                true,
                "Cluster State"
            )
        )
        val requestClusterHealth = Request("/_cluster/health", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestClusterHealth,
                true,
                "Cluster Health"
            )
        )
        val requestTemplates = Request("/_template", "{}", Method.GET)
        actionPopupGroup.add(
            RunQueryAction(
                this,
                requestTemplates,
                true,
                "Templates"
            )
        )

        actionPopupGroup.addSeparator()
        actionPopupGroup.add(CloseIndexAction(this))
        actionPopupGroup.add(OpenIndexAction(this))
        actionPopupGroup.add(RemoveAction(this))

        PopupHandler.installPopupHandler(tree, actionPopupGroup, "POPUP", ActionManager.getInstance())
    }

    fun openQueryEditorForIndex() {
        val index = getSelectedIndex()
        if (index != null) {
            val body = "{\n  \"from\": 0,\n  \"size\": 10,\n  \"query\": {\n    \"match_all\": {}\n  }\n}"
            val request = Request("/${index.name}/_search", body, Method.POST)
            openQueryEditor(index.cluster, request)
        }
    }

    fun openQueryEditor(cluster: ElasticsearchCluster, request: Request) {
        ElasticsearchFileSystem.instance?.openEditor(
            ElasticsearchFile(
                project,
                cluster,
                request
            )
        )
    }

    fun getSelectedClusters(): List<ElasticsearchCluster> {
        return tree.selectionPaths.orEmpty().asSequence()
            .map { it.lastPathComponent as DefaultMutableTreeNode }
            .map { it.userObject }
            .map {
                when (it) {
                    is ClusterNodeDescriptor -> {
                        it.element
                    }
                    else -> if (it is IndexNodeDescriptor) {
                        (it.element).cluster
                    } else {
                        null
                    }
                }
            }
            .filterNotNull()
            .toList()
    }

    fun getSelectedCluster(): ElasticsearchCluster? {
        return (getSelected() as? ClusterNodeDescriptor)?.element
    }

    fun getSelectedIndex(): ElasticsearchIndex? {
        return (getSelected() as? IndexNodeDescriptor)?.element
    }

    fun getSelected(): Any? {
        if (tree.selectionCount != 1) {
            return null
        }
        val path: TreePath = tree.selectionPath ?: return null
        val node = path.lastPathComponent as DefaultMutableTreeNode
        return node.userObject
    }

    private fun createToolbarPanel(): JPanel {
        val group = DefaultActionGroup()
        group.add(AddClusterAction(this))
        group.add(DuplicateClusterAction(this))
        group.add(EditClusterAction(this))
        group.addSeparator()
        group.add(RefreshClustersAction(this))
        group.add(AutoRefreshClustersActionGroup(project))
        group.add(OpenQueryEditorAction(this))
        group.addSeparator()

        val treeExpander: TreeExpander = object : TreeExpander {
            override fun expandAll() {
                TreeUtil.expandAll(tree)
            }

            override fun collapseAll() {
                TreeUtil.collapseAll(tree, 1)
            }

            override fun canExpand(): Boolean {
                return elasticsearchManager.getClusters().isNotEmpty()
            }

            override fun canCollapse(): Boolean {
                return canExpand()
            }
        }

        var action = CommonActionsManager.getInstance().createExpandAllAction(treeExpander, this)
        action.templatePresentation.description = "Expand all cluster nodes"
        group.add(action)
        action = CommonActionsManager.getInstance().createCollapseAllAction(treeExpander, this)
        action.templatePresentation.description = "Collapse all cluster nodes"
        group.add(action)

        val actionToolBar = ActionManager.getInstance()
            .createActionToolbar("ElasticsearchExplorerToolBar", group, true)
        return JBUI.Panels.simplePanel(actionToolBar.component)
    }

    override fun dispose() {
        ToolTipManager.sharedInstance().unregisterComponent(tree)
        for (keyStroke in tree.registeredKeyStrokes) {
            tree.unregisterKeyboardAction(keyStroke)
        }
    }

    fun removeSelectedCluster(selectedCluster: ElasticsearchCluster) {
        elasticsearchManager.removeCluster(selectedCluster.label)
    }

}