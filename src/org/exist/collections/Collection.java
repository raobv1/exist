
/*
 *  Collection.java - eXist Open Source Native XML Database
 *  Copyright (C) 2001-03 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 * 
 * $Id$
 */
package org.exist.collections;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.TreeMap;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.Category;
import org.apache.xml.resolver.tools.CatalogResolver;
import org.exist.EXistException;
import org.exist.Indexer;
import org.exist.collections.triggers.Trigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.BLOBDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.security.Group;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.SecurityManager;
import org.exist.security.User;
import org.exist.storage.DBBroker;
import org.exist.storage.cache.Cacheable;
import org.exist.util.Configuration;
import org.exist.util.DOMStreamer;
import org.exist.util.SyntaxException;
import org.exist.util.VariableByteInputStream;
import org.exist.util.VariableByteOutputStream;
import org.exist.util.hashtable.Object2LongHashMap;
import org.w3c.dom.Node;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

/**
 * This class represents a collection in the database.
 * 
 * A collection maintains a list of sub-collections and documents.
 *  
 * @author wolf
 */
public class Collection
	extends Observable
	implements Comparable, EntityResolver, Cacheable {

	private final static Category LOG =
		Category.getInstance(Collection.class.getName());

	private final static String COLLECTION_CONFIG_FILE = "collection.xconf";

	private final static int VALIDATION_ENABLED = 0;
	private final static int VALIDATION_AUTO = 1;
	private final static int VALIDATION_DISABLED = 2;

	private int validation = VALIDATION_AUTO;

	private short collectionId = -1;

	// the documents contained in this collection
	private TreeMap documents = new TreeMap();

	// the name of this collection
	private String name;

	// the permissions assigned to this collection
	private Permission permissions = new Permission(0755);

	// stores child-collections with their storage address
	private Object2LongHashMap subcollections = new Object2LongHashMap(19);

	// temporary field for the storage address
	private long address = -1;

	// creation time
	private long created = 0;

	private CatalogResolver resolver;

	private List observers = null;

	private CollectionConfiguration configuration = null;
	private boolean triggersEnabled = true;

	private int refCount = 0;
	private int timestamp = 0;
	
	public Collection(String name) {
		this.name = name;
	}

	/**
	 *  Add a new sub-collection to the collection.
	 *
	 *@param  name
	 */
	synchronized public void addCollection(Collection child) {
		final int p = child.name.lastIndexOf('/') + 1;
		final String childName = child.name.substring(p);
		if (!subcollections.containsKey(childName))
			subcollections.put(childName, child.address);
	}

	/**
	 * Add a new sub-collection to the collection.
	 * 
	 * @param name
	 */
	synchronized public void addCollection(String name) {
		if (!subcollections.containsKey(name))
			subcollections.put(name, -1);
	}

	/**
	 * Update the specified child-collection.
	 * 
	 * @param child
	 */
	synchronized public void update(Collection child) {
		final int p = child.name.lastIndexOf('/') + 1;
		final String childName = child.name.substring(p);
		subcollections.remove(childName);
		subcollections.put(childName, child.address);
	}

	/**
	 *  Add a document to the collection.
	 *
	 *@param  doc 
	 */
	synchronized public void addDocument(DBBroker broker, DocumentImpl doc) {
		if (doc.getDocId() < 0)
			doc.setDocId(broker.getNextDocId(this));
		documents.put(doc.getFileName(), doc);
	}

	/**
	 *  Return an iterator over all subcollections.
	 * 
	 * The list of subcollections is copied first, so modifications
	 * via the iterator have no affect.
	 *
	 *@return    Description of the Return Value
	 */
	synchronized public Iterator collectionIterator() {
		return subcollections.stableIterator();
	}

	/**
	 * Load all collections being descendants of this collections
	 * and return them in a List.
	 * 
	 * @return List
	 */
	public synchronized List getDescendants(DBBroker broker, User user) {
		final ArrayList cl = new ArrayList(subcollections.size());
		Collection child;
		String childName;
		for (Iterator i = subcollections.iterator(); i.hasNext();) {
			childName = (String) i.next();
			child = broker.getCollection(name + '/' + childName);
			if (permissions.validate(user, Permission.READ)) {
				cl.add(child);
				if (child.getChildCollectionCount() > 0)
					cl.addAll(child.getDescendants(broker, user));
			}
		}
		return cl;
	}

	/**
	 * Retrieve all documents contained in this collections.
	 * 
	 * If recursive is true, documents from sub-collections are
	 * included.
	 * 
	 * @param user
	 * @param recursive
	 * @return
	 */
	public synchronized DocumentSet allDocs(
		DBBroker broker,
		DocumentSet docs,
		boolean recursive) {
		getDocuments(docs);
		if (recursive)
			allDocs(broker, docs);
		return docs;
	}

	private DocumentSet allDocs(DBBroker broker, DocumentSet docs) {
		Collection child;
		String childName;
		long addr;
		for (Iterator i = subcollections.iterator(); i.hasNext();) {
			childName = (String) i.next();
			addr = subcollections.get(childName);
			if (addr < 0)
				child = broker.getCollection(name + '/' + childName);
			else
				child = broker.getCollection(name + '/' + childName, addr);
			if (permissions.validate(broker.getUser(), Permission.READ)) {
				child.getDocuments(docs);
				if (child.getChildCollectionCount() > 0)
					child.allDocs(broker, docs);
			}
		}
		return docs;
	}

	/**
	 * Add all documents to the specified document set.
	 *  
	 * @param docs
	 */
	public synchronized void getDocuments(DocumentSet docs) {
		docs.addCollection(this);
		docs.addAll(documents.values());
	}

	public int compareTo(Object obj) {
		Collection other = (Collection) obj;
		if (collectionId == other.collectionId)
			return 0;
		else if (collectionId < other.collectionId)
			return -1;
		else
			return 1;
	}

	public boolean equals(Object obj) {
		if (!(obj instanceof Collection))
			return false;
		return ((Collection) obj).collectionId == collectionId;
	}

	/**
	 *  Return the number of child-collections managed by this
	 * collection.
	 *
	 *@return    The childCollectionCount value
	 */
	public synchronized int getChildCollectionCount() {
		return subcollections.size();
	}

	/**
	 *  Get a child-document.
	 *
	 *@param  name  Description of the Parameter
	 *@return       The document value
	 */
	public synchronized DocumentImpl getDocument(String name) {
		return (DocumentImpl) documents.get(name);
	}

	/**
	 *  Returns the number of documents in this collection.
	 *
	 *@return    The documentCount value
	 */
	public synchronized int getDocumentCount() {
		return documents.size();
	}

	/**
	 *  Get the internal id.
	 *
	 *@return    The id value
	 */
	public short getId() {
		return collectionId;
	}

	/**
	 *  Get the name of this collection.
	 *
	 *@return    The name value
	 */
	public String getName() {
		return name;
	}

	/**
	 *  Returns the parent-collection.
	 *
	 *@return    The parent-collection or null if this
	 *is the root collection.
	 */
	public synchronized Collection getParent(DBBroker broker) {
		if (name.equals("/db"))
			return null;
		String parent =
			(name.lastIndexOf("/") < 1
				? "/db"
				: name.substring(0, name.lastIndexOf("/")));
		return broker.getCollection(parent);
	}

	/**
	 *  Gets the permissions attribute of the Collection object
	 *
	 *@return    The permissions value
	 */
	public synchronized Permission getPermissions() {
		return permissions;
	}

	/**
	 *  Check if the collection has a child document.
	 *
	 *@param  name  the name (without path) of the document
	 *@return  
	 */
	public synchronized boolean hasDocument(String name) {
		return getDocument(name) != null;
	}

	/**
	 *  Check if the collection has a sub-collection.
	 *
	 *@param  name  the name of the subcollection (without path).
	 *@return  
	 */
	public synchronized boolean hasSubcollection(String name) {
		return subcollections.containsKey(name);
	}

	/**
	 *  Returns an iterator on the child-documents in this collection.
	 *
	 *@return
	 */
	public synchronized Iterator iterator() {
		return documents.values().iterator();
	}

	/**
	 * Read collection contents from the stream.
	 * 
	 * @param istream
	 * @throws IOException
	 */
	public void read(DBBroker broker, VariableByteInputStream istream)
		throws IOException {
		collectionId = istream.readShort();
		final int collLen = istream.readInt();
		String sub;
		subcollections = new Object2LongHashMap(collLen);
		for (int i = 0; i < collLen; i++)
			subcollections.put(istream.readUTF(), istream.readLong());

		final SecurityManager secman =
			broker.getBrokerPool().getSecurityManager();
		final int uid = istream.readInt();
		final int gid = istream.readInt();
		final int perm = (istream.readByte() & 0777);
		if (secman == null) {
			permissions.setOwner(SecurityManager.DBA_USER);
			permissions.setGroup(SecurityManager.DBA_GROUP);
		} else {
			permissions.setOwner(secman.getUser(uid));
			permissions.setGroup(secman.getGroup(gid).getName());
		}
		permissions.setPermissions(perm);
		created = istream.readLong();
		DocumentImpl doc;
		byte resourceType;
		try {
			while (istream.available() > 0) {
				resourceType = istream.readByte();
				switch (resourceType) {
					case DocumentImpl.XML_FILE :
						doc = new DocumentImpl(broker, this);
						break;
					case DocumentImpl.BINARY_FILE :
						doc = new BLOBDocument(broker, this);
						break;
					case -1:
						return; // EOF found
					default :
						LOG.warn("unknown resource type: " + resourceType);
						throw new IOException("unable to determine resource type while reading collection " +
							getName());
				}
				doc.read(istream);
				addDocument(broker, doc);
			}
		} catch (EOFException e) {
		}
	}

	/**
	 *  Remove the specified sub-collection.
	 *
	 *@param  name  Description of the Parameter
	 */
	public synchronized void removeCollection(String name) {
		subcollections.remove(name);
	}

	/**
	 *  Remove the specified document from the collection.
	 *
	 *@param  name  Description of the Parameter
	 */
	synchronized public void removeDocument(DBBroker broker, String docname)
		throws PermissionDeniedException, TriggerException {
		Trigger trigger = null;
		if (!docname.equals(COLLECTION_CONFIG_FILE)) {
			if (triggersEnabled) {
				CollectionConfiguration config = getConfiguration(broker);
				if (config != null)
					trigger = config.getTrigger(Trigger.REMOVE_DOCUMENT_EVENT);
			}
		} else
			configuration = null;
		String path = getName() + '/' + docname;
		DocumentImpl doc = getDocument(path);
		if (doc == null)
			return;
		if (trigger != null && triggersEnabled) {
			trigger.prepare(
				Trigger.REMOVE_DOCUMENT_EVENT,
				broker,
				docname,
				doc);
		}
		broker.removeDocument(path);
		documents.remove(path);
		broker.saveCollection(this);
	}

	synchronized public void removeDocument(String path) {
		documents.remove(path);
	}

	synchronized public void removeBinaryResource(DBBroker broker, String docname)
	throws PermissionDeniedException {
		 String path = getName() + '/' + docname;
		DocumentImpl doc = getDocument(path);
		if(doc == null)
			return;
		if(doc.getResourceType() != DocumentImpl.BINARY_FILE)
			throw new PermissionDeniedException("document " + docname + " is not a binary object");
		broker.removeBinaryResource((BLOBDocument)doc);
		documents.remove(path);
		broker.saveCollection(this);
	}
	
	public DocumentImpl addDocument(DBBroker broker, String name, String data)
		throws
			EXistException,
			PermissionDeniedException,
			TriggerException,
			SAXException {
		return addDocument(broker, name, data, false);
	}

	public DocumentImpl addDocument(
		DBBroker broker,
		String name,
		String data,
		boolean privileged)
		throws
			EXistException,
			PermissionDeniedException,
			TriggerException,
			SAXException {
		if (broker.isReadOnly())
			throw new PermissionDeniedException("Database is read-only");
		DocumentImpl document, oldDoc = null;
		synchronized (this) {
			oldDoc = getDocument(getName() + '/' + name);
		}
		if (oldDoc != null) {
			// do we have permissions for update?
			if (!oldDoc
				.getPermissions()
				.validate(broker.getUser(), Permission.UPDATE))
				throw new PermissionDeniedException(
					"document exists and update " + "is not allowed");
			// do we have write permissions?
		} else if (
			!getPermissions().validate(broker.getUser(), Permission.WRITE))
			throw new PermissionDeniedException(
				"not allowed to write to collection " + getName());
		// if an old document exists, save the new document with a temporary
		// document name
		if (oldDoc != null) {
			document = new DocumentImpl(broker, getName() + "/__" + name, this);
			document.setCreated(oldDoc.getCreated());
			document.setLastModified(System.currentTimeMillis());
			document.setPermissions(oldDoc.getPermissions());
		} else {
			document = new DocumentImpl(broker, getName() + '/' + name, this);
			document.setCreated(System.currentTimeMillis());
			document.getPermissions().setOwner(broker.getUser());
			document.getPermissions().setGroup(
				broker.getUser().getPrimaryGroup());
		}
		// setup triggers
		Trigger trigger = null;
		if (!name.equals(COLLECTION_CONFIG_FILE)) {
			if (triggersEnabled) {
				CollectionConfiguration config = getConfiguration(broker);
				if (config != null) {
					if (oldDoc == null)
						trigger =
							config.getTrigger(Trigger.STORE_DOCUMENT_EVENT);
					else
						trigger =
							config.getTrigger(Trigger.UPDATE_DOCUMENT_EVENT);
				}
			}
		} else
			// set configuration to null if we are updating collection.xconf
			configuration = null;
		Indexer parser = new Indexer(broker);
		parser.setDocument(document);

		// add observers to the indexer
		Observer observer;
		broker.deleteObservers();
		if (observers != null) {
			for (Iterator i = observers.iterator(); i.hasNext();) {
				observer = (Observer) i.next();
				parser.addObserver(observer);
				broker.addObserver(observer);
			}
		}
		// prepare the SAX parser
		parser.setValidating(true);
		XMLReader reader = getReader(broker);
		reader.setEntityResolver(this);

		if (trigger != null && triggersEnabled) {
			reader.setContentHandler(trigger.getInputHandler());
			reader.setProperty(
				"http://xml.org/sax/properties/lexical-handler",
				trigger.getLexicalInputHandler());
			trigger.setOutputHandler(parser);
			trigger.setValidating(true);
			// prepare the trigger
			trigger.prepare(
				oldDoc == null
					? Trigger.STORE_DOCUMENT_EVENT
					: Trigger.UPDATE_DOCUMENT_EVENT,
				broker,
				name,
				oldDoc);
		} else {
			reader.setContentHandler(parser);
			reader.setProperty(
				"http://xml.org/sax/properties/lexical-handler",
				parser);
		}
		reader.setErrorHandler(parser);

		// first pass: parse the document to determine tree structure
		LOG.debug("validating document " + name);
		InputSource source = new InputSource(new StringReader(data));
		try {
			reader.parse(source);
		} catch (IOException e) {
			throw new EXistException(e);
		}
		document.setMaxDepth(document.getMaxDepth() + 1);
		document.calculateTreeLevelStartPoints();
		// new document is valid: remove old document 
		if (oldDoc != null) {
			LOG.debug("removing old document " + oldDoc.getFileName());
			broker.removeDocument(oldDoc.getFileName());
			document.setFileName(oldDoc.getFileName());
		}
		synchronized (this) {
			addDocument(broker, document);
		}

		parser.setValidating(false);
		if (trigger != null)
			trigger.setValidating(false);
		// reset the input source
		source = new InputSource(new StringReader(data));

		// second pass: store the document
		LOG.debug("storing document ...");
		try {
			reader.parse(source);
		} catch (IOException e) {
			throw new EXistException(e);
		}

		synchronized (this) {
			broker.addDocument(this, document);
			broker.closeDocument();
			broker.flush();
			// if we are running in privileged mode (e.g. backup/restore)
			// notify the SecurityManager about changes
			if (document.getFileName().equals("/db/system/users.xml")
				&& privileged == false) {
				// inform the security manager that system data has changed
				LOG.debug("users.xml changed");
				broker.getBrokerPool().reloadSecurityManager(broker);
			}
		}
		broker.deleteObservers();
		return document;
	}

	public DocumentImpl addDocument(
		DBBroker broker,
		String name,
		InputSource source)
		throws
			EXistException,
			PermissionDeniedException,
			TriggerException,
			SAXException {
		return addDocument(broker, name, source, false);
	}

	public DocumentImpl addDocument(
		DBBroker broker,
		String name,
		InputSource source,
		boolean privileged)
		throws
			EXistException,
			PermissionDeniedException,
			SAXException,
			TriggerException {
		if (broker.isReadOnly())
			throw new PermissionDeniedException("Database is read-only");
		DocumentImpl document, oldDoc = null;
		synchronized (this) {
			oldDoc = getDocument(getName() + '/' + name);
		}
		if (oldDoc != null) {
			// do we have permissions for update?
			if (!oldDoc
				.getPermissions()
				.validate(broker.getUser(), Permission.UPDATE))
				throw new PermissionDeniedException(
					"document exists and update " + "is not allowed");
			// do we have write permissions?
		} else if (
			!getPermissions().validate(broker.getUser(), Permission.WRITE))
			throw new PermissionDeniedException(
				"not allowed to write to collection " + getName());
		// if an old document exists, save the new document with a temporary
		// document name
		if (oldDoc != null) {
			document = new DocumentImpl(broker, getName() + "/__" + name, this);
			document.setCreated(oldDoc.getCreated());
			document.setLastModified(System.currentTimeMillis());
			document.setPermissions(oldDoc.getPermissions());
		} else {
			document = new DocumentImpl(broker, getName() + '/' + name, this);
			document.setCreated(System.currentTimeMillis());
			document.getPermissions().setOwner(broker.getUser());
			document.getPermissions().setGroup(
				broker.getUser().getPrimaryGroup());
		}
		// setup triggers
		Trigger trigger = null;
		if (!name.equals(COLLECTION_CONFIG_FILE)) {
			if (triggersEnabled) {
				CollectionConfiguration config = getConfiguration(broker);
				if (config != null) {
					if (oldDoc == null)
						trigger =
							config.getTrigger(Trigger.STORE_DOCUMENT_EVENT);
					else
						trigger =
							config.getTrigger(Trigger.UPDATE_DOCUMENT_EVENT);
				}
			}
		} else
			// set configuration to null if we are updating collection.xconf
			configuration = null;
		Indexer parser = new Indexer(broker);
		parser.setDocument(document);

		// add observers to the indexer
		Observer observer;
		broker.deleteObservers();
		if (observers != null) {
			for (Iterator i = observers.iterator(); i.hasNext();) {
				observer = (Observer) i.next();
				parser.addObserver(observer);
				broker.addObserver(observer);
			}
		}
		// prepare the SAX parser
		parser.setValidating(true);
		XMLReader reader = getReader(broker);
		reader.setEntityResolver(this);

		if (trigger != null && triggersEnabled) {
			reader.setContentHandler(trigger.getInputHandler());
			reader.setProperty(
				"http://xml.org/sax/properties/lexical-handler",
				trigger.getLexicalInputHandler());
			trigger.setOutputHandler(parser);
			trigger.setLexicalOutputHandler(parser);
			trigger.setValidating(true);
			// prepare the trigger
			trigger.prepare(
				oldDoc == null
					? Trigger.STORE_DOCUMENT_EVENT
					: Trigger.UPDATE_DOCUMENT_EVENT,
				broker,
				name,
				oldDoc);
		} else {
			reader.setContentHandler(parser);
			reader.setProperty(
				"http://xml.org/sax/properties/lexical-handler",
				parser);
		}
		reader.setErrorHandler(parser);

		// first pass: parse the document to determine tree structure
		LOG.debug("validating document " + name);
		try {
			reader.parse(source);
		} catch (IOException e) {
			throw new EXistException(e);
		}
		document.setMaxDepth(document.getMaxDepth() + 1);
		document.calculateTreeLevelStartPoints();
		// new document is valid: remove old document 
		if (oldDoc != null) {
			LOG.debug("removing old document " + oldDoc.getFileName());
			broker.removeDocument(oldDoc.getFileName());
			document.setFileName(oldDoc.getFileName());
		}
		synchronized (this) {
			addDocument(broker, document);
		}

		parser.setValidating(false);
		if (trigger != null)
			trigger.setValidating(false);
		// reset the input source
		try {
			final InputStream is = source.getByteStream();
			if (is != null)
				is.reset();
			else {
				final Reader cs = source.getCharacterStream();
				if (cs != null)
					cs.reset();
			}
		} catch (IOException e) {
			LOG.debug("could not reset input source", e);
		}

		// second pass: store the document
		LOG.debug("storing document ...");
		try {
			reader.parse(source);
		} catch (IOException e) {
			throw new EXistException(e);
		}

		synchronized (this) {
			broker.addDocument(this, document);
			broker.closeDocument();
			broker.flush();
			// if we are running in privileged mode (e.g. backup/restore)
			// notify the SecurityManager about changes
			if (document.getFileName().equals("/db/system/users.xml")
				&& privileged == false) {
				// inform the security manager that system data has changed
				LOG.debug("users.xml changed");
				broker.getBrokerPool().reloadSecurityManager(broker);
			}
		}
		broker.deleteObservers();
		return document;
	}

	public DocumentImpl addDocument(DBBroker broker, String name, Node node)
		throws
			EXistException,
			PermissionDeniedException,
			TriggerException,
			SAXException {
		return addDocument(broker, name, node, false);
	}

	public DocumentImpl addDocument(
		DBBroker broker,
		String name,
		Node node,
		boolean privileged)
		throws
			EXistException,
			PermissionDeniedException,
			TriggerException,
			SAXException {
		Indexer parser = new Indexer(broker);
		if (broker.isReadOnly())
			throw new PermissionDeniedException("Database is read-only");
		DocumentImpl document, oldDoc = null;
		synchronized (this) {
			oldDoc = getDocument(getName() + '/' + name);
		}
		if (oldDoc != null) {
			// do we have permissions for update?
			if (!oldDoc
				.getPermissions()
				.validate(broker.getUser(), Permission.UPDATE))
				throw new PermissionDeniedException(
					"document exists and update " + "is not allowed");
			// no: do we have write permissions?
		} else if (
			!getPermissions().validate(broker.getUser(), Permission.WRITE))
			throw new PermissionDeniedException(
				"not allowed to write to collection " + getName());
		// if an old document exists, save the new document with a temporary
		// document name
		if (oldDoc != null) {
			document = new DocumentImpl(broker, getName() + "/__" + name, this);
			document.setCreated(oldDoc.getCreated());
			document.setLastModified(System.currentTimeMillis());
			document.setPermissions(oldDoc.getPermissions());
		} else {
			document = new DocumentImpl(broker, getName() + '/' + name, this);
			document.setCreated(System.currentTimeMillis());
			document.getPermissions().setOwner(broker.getUser());
			document.getPermissions().setGroup(
				broker.getUser().getPrimaryGroup());
		}
		// setup triggers
		Trigger trigger = null;
		if (!name.equals(COLLECTION_CONFIG_FILE)) {
			if (triggersEnabled) {
				CollectionConfiguration config = getConfiguration(broker);
				if (config != null) {
					if (oldDoc == null)
						trigger =
							config.getTrigger(Trigger.STORE_DOCUMENT_EVENT);
					else
						trigger =
							config.getTrigger(Trigger.UPDATE_DOCUMENT_EVENT);
				}
			}
		} else
			// set configuration to null if we are updating collection.xconf
			configuration = null;
		parser.setDocument(document);

		// add observers to the indexer
		Observer observer;
		broker.deleteObservers();
		if (observers != null) {
			for (Iterator i = observers.iterator(); i.hasNext();) {
				observer = (Observer) i.next();
				parser.addObserver(observer);
				broker.addObserver(observer);
			}
		}
		parser.setValidating(true);
		DOMStreamer streamer = new DOMStreamer();
		if (trigger != null && triggersEnabled) {
			streamer.setContentHandler(trigger.getInputHandler());
			streamer.setLexicalHandler(trigger.getLexicalInputHandler());
			trigger.setOutputHandler(parser);
			trigger.setValidating(true);
			// prepare the trigger
			trigger.prepare(
				oldDoc == null
					? Trigger.STORE_DOCUMENT_EVENT
					: Trigger.UPDATE_DOCUMENT_EVENT,
				broker,
				name,
				oldDoc);
		} else {
			streamer.setContentHandler(parser);
			streamer.setLexicalHandler(parser);
		}

		// first pass: parse the document to determine tree structure
		LOG.debug("validating document " + name);
		streamer.stream(node);
		document.setMaxDepth(document.getMaxDepth() + 1);
		document.calculateTreeLevelStartPoints();
		// new document is valid: remove old document 
		if (oldDoc != null) {
			LOG.debug("removing old document " + oldDoc.getFileName());
			broker.removeDocument(oldDoc.getFileName());
			document.setFileName(oldDoc.getFileName());
		}
		synchronized (this) {
			addDocument(broker, document);
		}

		parser.setValidating(false);
		if (trigger != null)
			trigger.setValidating(false);

		// second pass: store the document
		LOG.debug("storing document ...");
		streamer.stream(node);

		synchronized (this) {
			broker.addDocument(this, document);
			broker.closeDocument();
			broker.flush();
			// if we are running in privileged mode (e.g. backup/restore)
			// notify the SecurityManager about changes
			if (document.getFileName().equals("/db/system/users.xml")
				&& privileged == false) {
				// inform the security manager that system data has changed
				LOG.debug("users.xml changed");
				broker.getBrokerPool().reloadSecurityManager(broker);
			}
		}
		broker.deleteObservers();
		return document;
	}

	public synchronized BLOBDocument addBinaryResource(
		DBBroker broker,
		String name,
		byte[] data)
		throws EXistException, PermissionDeniedException {
		if (broker.isReadOnly())
			throw new PermissionDeniedException("Database is read-only");
		DocumentImpl oldDoc = null;
		synchronized (this) {
			oldDoc = getDocument(getName() + '/' + name);
		}
		if (oldDoc != null) {
			// do we have permissions for update?
			if (!oldDoc
				.getPermissions()
				.validate(broker.getUser(), Permission.UPDATE))
				throw new PermissionDeniedException(
					"document exists and update " + "is not allowed");
		// no: do we have write permissions?
		} else if (
			!getPermissions().validate(broker.getUser(), Permission.WRITE))
			throw new PermissionDeniedException(
				"not allowed to write to collection " + getName());
		
		BLOBDocument blob = new BLOBDocument(broker, getName() + '/' + name, this);
		if (oldDoc != null) {
			blob.setCreated(oldDoc.getCreated());
			blob.setLastModified(System.currentTimeMillis());
			blob.setPermissions(oldDoc.getPermissions());
			
			LOG.debug("removing old document " + oldDoc.getFileName());
			broker.removeDocument(oldDoc.getFileName());
		} else {
			blob.setCreated(System.currentTimeMillis());
			blob.getPermissions().setOwner(broker.getUser());
			blob.getPermissions().setGroup(
				broker.getUser().getPrimaryGroup());
		}
		broker.storeBinaryResource(blob, data);
		synchronized (this) {
			addDocument(broker, blob);
			broker.addDocument(this, blob);
		}
		return blob;
	}

	public synchronized void setId(short id) {
		this.collectionId = id;
	}

	public synchronized void setPermissions(int mode) {
		permissions.setPermissions(mode);
	}

	public synchronized void setPermissions(String mode)
		throws SyntaxException {
		permissions.setPermissions(mode);
	}

	/**
	 * Set permissions for the collection.
	 * 
	 * @param permissions
	 */
	public synchronized void setPermissions(Permission permissions) {
		this.permissions = permissions;
	}

	/**
	 * Write collection contents to stream.
	 * 
	 * @param ostream
	 * @throws IOException
	 */
	public void write(DBBroker broker, VariableByteOutputStream ostream)
		throws IOException {
		ostream.writeShort(collectionId);
		ostream.writeInt(subcollections.size());
		String childColl;
		for (Iterator i = subcollections.iterator(); i.hasNext();) {
			childColl = (String) i.next();
			ostream.writeUTF(childColl);
			ostream.writeLong(subcollections.get(childColl));
		}
		org.exist.security.SecurityManager secman =
			broker.getBrokerPool().getSecurityManager();
		if (secman == null) {
			ostream.writeInt(1);
			ostream.writeInt(1);
		} else {
			User user = secman.getUser(permissions.getOwner());
			Group group = secman.getGroup(permissions.getOwnerGroup());
			ostream.writeInt(user.getUID());
			ostream.writeInt(group.getId());
		}
		ostream.writeByte((byte) permissions.getPermissions());
		ostream.writeLong(created);
		DocumentImpl doc;
		for (Iterator i = iterator(); i.hasNext();) {
			doc = (DocumentImpl) i.next();
			doc.write(ostream);
		}
	}

	public CollectionConfiguration getConfiguration(DBBroker broker) {
		if (configuration == null)
			configuration = readCollectionConfiguration(broker);
		return configuration;
	}

	private CollectionConfiguration readCollectionConfiguration(DBBroker broker) {
		DocumentImpl doc =
			getDocument(getName() + '/' + COLLECTION_CONFIG_FILE);
		if (doc != null) {
			LOG.debug("found collection.xconf");
			triggersEnabled = false;
			try {
				return new CollectionConfiguration(broker, this, doc);
			} catch (CollectionConfigurationException e) {
				LOG.warn(e.getMessage(), e);
			} finally {
				triggersEnabled = true;
			}
		}
		return null;
	}

	/**
	 * Set the internal storage address of the collection data.
	 * 
	 * @param addr
	 */
	public void setAddress(long addr) {
		this.address = addr;
	}

	public void setCreationTime(long ms) {
		created = ms;
	}

	public long getCreationTime() {
		return created;
	}

	public void setTriggersEnabled(boolean enabled) {
		this.triggersEnabled = enabled;
	}

	private XMLReader getReader(DBBroker broker)
		throws EXistException, SAXException {
		Configuration config = broker.getConfiguration();
		// get validation settings
		String option = (String) config.getProperty("indexer.validation");
		if (option != null) {
			if (option.equals("true"))
				validation = VALIDATION_ENABLED;
			else if (option.equals("auto"))
				validation = VALIDATION_AUTO;
			else
				validation = VALIDATION_DISABLED;
		}
		resolver = (CatalogResolver) config.getProperty("resolver");
		// create a SAX parser
		SAXParserFactory saxFactory = SAXParserFactory.newInstance();
		if (validation == VALIDATION_AUTO || validation == VALIDATION_ENABLED)
			saxFactory.setValidating(true);
		else
			saxFactory.setValidating(false);
		saxFactory.setNamespaceAware(true);
		try {
			setFeature(
				saxFactory,
				"http://xml.org/sax/features/namespace-prefixes",
				true);
			setFeature(
				saxFactory,
				"http://apache.org/xml/features/validation/dynamic",
				validation == VALIDATION_AUTO);
			setFeature(
				saxFactory,
				"http://apache.org/xml/features/validation/schema",
				validation == VALIDATION_AUTO
					|| validation == VALIDATION_ENABLED);
			SAXParser sax = saxFactory.newSAXParser();
			XMLReader parser = sax.getXMLReader();
			return parser;
		} catch (ParserConfigurationException e) {
			LOG.warn(e);
			throw new EXistException(e);
		}
	}

	/**
	 * Try to resolve external entities.
	 * 
	 * This method forwards the request to the resolver. If that fails,
	 * the method replaces absolute file names with relative ones 
	 * and retries to resolve. This makes it possible to use relative
	 * file names in the catalog.
	 * 
	 * @see org.xml.sax.EntityResolver#resolveEntity(java.lang.String, java.lang.String)
	 */
	public InputSource resolveEntity(String publicId, String systemId)
		throws SAXException, IOException {
		InputSource is = resolver.resolveEntity(publicId, systemId);
		// if resolution failed and publicId == null,
		// try to make absolute file names relative and retry
		if (is == null) {
			if (publicId != null)
				return null;
			URL url = new URL(systemId);
			if (url.getProtocol().equals("file")) {
				String path = url.getPath();
				File f = new File(path);
				if (!f.canRead())
					return resolver.resolveEntity(null, f.getName());
				else
					return new InputSource(f.getAbsolutePath());
			} else
				return new InputSource(url.openStream());
		}
		return is;
	}

	private void setFeature(
		SAXParserFactory factory,
		String feature,
		boolean value) {
		try {
			factory.setFeature(feature, value);
		} catch (SAXNotRecognizedException e) {
			LOG.warn(e);
		} catch (SAXNotSupportedException snse) {
			LOG.warn(snse);
		} catch (ParserConfigurationException pce) {
			LOG.warn(pce);
		}
	}

	/* (non-Javadoc)
	 * @see java.util.Observable#addObserver(java.util.Observer)
	 */
	public synchronized void addObserver(Observer o) {
		if (observers == null)
			observers = new ArrayList(1);
		if (!observers.contains(o))
			observers.add(o);
	}

	/* (non-Javadoc)
	 * @see java.util.Observable#deleteObservers()
	 */
	public synchronized void deleteObservers() {
		if(observers != null)
			observers.clear();
	}
	
	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getKey()
	 */
	public long getKey() {
		return collectionId;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getReferenceCount()
	 */
	public int getReferenceCount() {
		return refCount;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#incReferenceCount()
	 */
	public int incReferenceCount() {
		return ++refCount;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#decReferenceCount()
	 */
	public int decReferenceCount() {
		return refCount > 0 ? --refCount : 0;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#setReferenceCount(int)
	 */
	public void setReferenceCount(int count) {
		refCount = count;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#setTimestamp(int)
	 */
	public void setTimestamp(int timestamp) {
		this.timestamp = timestamp;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#getTimestamp()
	 */
	public int getTimestamp() {
		return timestamp;
	}

	/* (non-Javadoc)
	 * @see org.exist.storage.cache.Cacheable#release()
	 */
	public void sync() {
	}
}
