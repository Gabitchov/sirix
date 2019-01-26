package org.sirix.access;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sirix.access.conf.DatabaseConfiguration;
import org.sirix.api.Database;
import org.sirix.api.NodeReadTrx;
import org.sirix.api.NodeWriteTrx;
import org.sirix.api.ResourceManager;
import org.sirix.node.SirixDeweyID;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.delegates.StructNodeDelegate;
import org.sirix.node.interfaces.Node;
import org.sirix.node.json.JsonDocumentRootNode;
import org.sirix.node.xdm.XdmDocumentRootNode;
import org.sirix.settings.Fixed;

public enum DatabaseType {
  XDM {
    @SuppressWarnings("unchecked")
    @Override
    public <R extends ResourceManager<? extends NodeReadTrx, ? extends NodeWriteTrx>, S extends ResourceStore<R>> Database<R> createDatabase(
        DatabaseConfiguration dbConfig, S store) {
      return (Database<R>) new LocalXdmDatabase(dbConfig, (XdmResourceStore) store);
    }

    @Override
    public Node getDocumentNode(SirixDeweyID id) {
      final NodeDelegate nodeDel = new NodeDelegate(Fixed.DOCUMENT_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), Fixed.NULL_NODE_KEY.getStandardProperty(), 0, id);
      final StructNodeDelegate structDel = new StructNodeDelegate(nodeDel, Fixed.NULL_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), Fixed.NULL_NODE_KEY.getStandardProperty(), 0, 0);

      return new XdmDocumentRootNode(nodeDel, structDel);
    }
  },

  JSON {
    @SuppressWarnings("unchecked")
    @Override
    public <R extends ResourceManager<? extends NodeReadTrx, ? extends NodeWriteTrx>, S extends ResourceStore<R>> Database<R> createDatabase(
        DatabaseConfiguration dbConfig, S store) {
      return (Database<R>) new LocalJsonDatabase(dbConfig, (JsonResourceStore) store);
    }

    @Override
    public Node getDocumentNode(SirixDeweyID id) {
      final NodeDelegate nodeDel = new NodeDelegate(Fixed.DOCUMENT_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), Fixed.NULL_NODE_KEY.getStandardProperty(), 0, null);
      final StructNodeDelegate structDel = new StructNodeDelegate(nodeDel, Fixed.NULL_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), Fixed.NULL_NODE_KEY.getStandardProperty(), 0, 0);

      return new JsonDocumentRootNode(nodeDel, structDel);
    }
  };

  private static final Map<String, DatabaseType> stringToEnum =
      Stream.of(values()).collect(Collectors.toMap(Object::toString, e -> e));

  public static Optional<DatabaseType> fromString(String symbol) {
    return Optional.ofNullable(stringToEnum.get(symbol));
  }

  public abstract <R extends ResourceManager<? extends NodeReadTrx, ? extends NodeWriteTrx>, S extends ResourceStore<R>> Database<R> createDatabase(
      DatabaseConfiguration dbConfig, S store);

  public abstract Node getDocumentNode(SirixDeweyID id);
}