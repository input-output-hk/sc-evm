package io.iohk.scevm.mpt.visitors

import io.iohk.scevm.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode, MptNode, MptStorage, Node, NullNode}

class MptConstructionVisitor(source: MptStorage) extends MptVisitor[MptNode] {

  def visitLeaf(leaf: LeafNode): MptNode =
    leaf

  def visitHash(hashNode: HashNode): HashNodeResult[MptNode] =
    ResolveResult(source.getNodeOrFail(hashNode.hash))

  override def visitNull(): MptNode =
    NullNode

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[MptNode] =
    new MptExtensionVisitor(extension, source)

  override def visitBranch(value: BranchNode): BranchVisitor[MptNode] = new MptBranchVisitor(value, source)
}

class MptBranchVisitor(branchNode: BranchNode, source: MptStorage) extends BranchVisitor[MptNode] {
  var resolvedChildren: List[MptNode] = List.empty

  override def visitChild(child: => MptNode): Unit =
    resolvedChildren = child :: resolvedChildren

  override def visitChild(): MptVisitor[MptNode] = new MptConstructionVisitor(source)

  override def visitTerminator(term: Option[Node.Hash]): Unit = ()

  override def done(): MptNode =
    branchNode.copy(children = resolvedChildren.reverse.toArray)
}

class MptExtensionVisitor(extensionNode: ExtensionNode, source: MptStorage) extends ExtensionVisitor[MptNode] {
  var resolvedNext = extensionNode.next

  override def visitNext(): MptVisitor[MptNode] = new MptConstructionVisitor(source)

  override def visitNext(value: => MptNode): Unit =
    resolvedNext = value

  override def done(): MptNode =
    extensionNode.copy(next = resolvedNext)
}
