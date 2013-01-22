/**
 * Copyright (C) 2010-2013 Axel Morgner, structr <structr@structr.org>
 *
 * This file is part of structr <http://structr.org>.
 *
 * structr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * structr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.web.entity.dom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.neo4j.graphdb.Direction;
import org.structr.common.Permission;
import org.structr.common.RelType;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.core.GraphObject;
import org.structr.core.Predicate;
import org.structr.core.Result;
import org.structr.core.Services;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.AbstractRelationship;
import org.structr.core.graph.StructrTransaction;
import org.structr.core.graph.TransactionCommand;
import org.structr.core.graph.search.Search;
import org.structr.core.graph.search.SearchAttribute;
import org.structr.core.graph.search.SearchNodeCommand;
import org.structr.core.property.CollectionProperty;
import org.structr.core.property.EntityProperty;
import org.structr.core.property.PropertyKey;
import org.structr.core.property.StringProperty;
import org.structr.web.common.Function;
import org.structr.web.common.OrderedTreeManager;
import org.structr.web.common.PageHelper;
import org.structr.web.common.RenderContext;
import org.structr.web.common.ThreadLocalMatcher;
import org.structr.web.entity.Renderable;
import org.structr.web.entity.relation.ChildrenRelationship;
import org.structr.web.servlet.HtmlServlet;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.w3c.dom.UserDataHandler;

/**
 * Combines AbstractNode and org.w3c.dom.Node.
 * 
 * @author Christian Morgner
 */

public abstract class DOMNode extends AbstractNode implements Node, Renderable, DOMAdoptable, DOMImportable {

	private static final Logger logger                                      = Logger.getLogger(DOMNode.class.getName());
	private static final ThreadLocalMatcher threadLocalTemplateMatcher      = new ThreadLocalMatcher("\\$\\{[^}]*\\}");
	private static final ThreadLocalMatcher threadLocalFunctionMatcher      = new ThreadLocalMatcher("([a-zA-Z0-9_]+)\\((.+)\\)");
	
	// ----- error messages for DOMExceptions -----
	protected static final String NO_MODIFICATION_ALLOWED_MESSAGE           = "Permission denied.";
	protected static final String INVALID_ACCESS_ERR_MESSAGE                = "Permission denied.";
	protected static final String INDEX_SIZE_ERR_MESSAGE                    = "Index out of range.";
	protected static final String CANNOT_SPLIT_TEXT_WITHOUT_PARENT          = "Cannot split text element without parent and/or owner document.";
	protected static final String WRONG_DOCUMENT_ERR_MESSAGE                = "Node does not belong to this document.";
	protected static final String HIERARCHY_REQUEST_ERR_MESSAGE_SAME_NODE   = "A node cannot accept itself as a child.";
	protected static final String HIERARCHY_REQUEST_ERR_MESSAGE_ANCESTOR    = "A node cannot accept its own ancestor as child.";
	protected static final String HIERARCHY_REQUEST_ERR_MESSAGE_DOCUMENT    = "A document may only have one document element.";
	protected static final String HIERARCHY_REQUEST_ERR_MESSAGE_ELEMENT     = "A document may only accept an html element as its document element.";
	protected static final String NOT_SUPPORTED_ERR_MESSAGE                 = "Node type not supported.";
	protected static final String NOT_FOUND_ERR_MESSAGE                     = "Node is not a child.";
	protected static final String NOT_SUPPORTED_ERR_MESSAGE_IMPORT_DOC      = "Document nodes cannot be imported into another document.";
	protected static final String NOT_SUPPORTED_ERR_MESSAGE_ADOPT_DOC       = "Document nodes cannot be adopted by another document.";
	protected static final String NOT_SUPPORTED_ERR_MESSAGE_RENAME          = "Renaming of nodes is not supported by this implementation.";
	
	protected static final Map<String, Function<String, String>> functions  = new LinkedHashMap<String, Function<String, String>>();
	
	public static final CollectionProperty<DOMNode> children                = new CollectionProperty<DOMNode>("children", DOMNode.class, RelType.CONTAINS, Direction.OUTGOING, true);
	public static final EntityProperty<DOMNode> parent                      = new EntityProperty<DOMNode>("parent", DOMNode.class, RelType.CONTAINS, Direction.INCOMING, false);
	public static final EntityProperty<Page> page                           = new EntityProperty<Page>("page", Page.class, RelType.PAGE, Direction.OUTGOING, true);

	private static OrderedTreeManager<DOMNode> treeManager                  = new OrderedTreeManager<DOMNode>(children, ChildrenRelationship.position, RelType.CONTAINS, RelType.NEXT_LIST_ENTRY);
	private static Set<Page> resultPages                                    = new HashSet<Page>();
		
	
	static {

		functions.put("md5", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				return ((s != null) && (s.length > 0) && (s[0] != null))
				       ? DigestUtils.md5Hex(s[0])
				       : null;

			}

		});
		functions.put("upper", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				return ((s != null) && (s.length > 0) && (s[0] != null))
				       ? s[0].toUpperCase()
				       : null;

			}

		});
		functions.put("lower", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				return ((s != null) && (s.length > 0) && (s[0] != null))
				       ? s[0].toLowerCase()
				       : null;

			}

		});
		functions.put("capitalize", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				return ((s != null) && (s.length > 0) && (s[0] != null))
				       ? StringUtils.capitalize(s[0])
				       : null;

			}

		});
		functions.put("if", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				if (s.length < 3) {

					return "";
				}

				if (s[0].equals("true")) {

					return s[1];
				} else {

					return s[2];
				}

			}

		});
		functions.put("equal", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				logger.log(Level.FINE, "Length: {0}", s.length);

				if (s.length < 2) {

					return "true";
				}

				logger.log(Level.FINE, "Comparing {0} to {1}", new java.lang.Object[] { s[0], s[1] });

				return s[0].equals(s[1])
				       ? "true"
				       : "false";

			}

		});
		functions.put("add", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				int result = 0;

				if (s != null) {

					for (int i = 0; i < s.length; i++) {

						try {

							result += Integer.parseInt(s[i]);

						} catch (Throwable t) {}

					}

				}

				return new Integer(result).toString();

			}

		});
		functions.put("active", new Function<String, String>() {

			@Override
			public String apply(String[] s) {

				if (s.length == 0) {

					return "";
				}

				String data = this.dataId.get();
				String page = this.pageId.get();

				if (data != null && page != null) {

					if (data.equals(page)) {

						// return first argument if condition is true
						return s[0];
					} else if (s.length > 1) {

						// return second argument if condition is false and second argument exists
						return s[1];
					}

				}

				return "";

			}

		});

	}
	
	// ----- public methods -----
	@Override
	public String toString() {
		
		return getClass().getSimpleName() + " (" + getTextContent() + ", " + treeManager.getChildPosition(this) + ")";
	}

	// ----- protected methods -----		
	protected void checkIsChild(Node otherNode) throws DOMException {
		
		if (otherNode instanceof DOMNode) {

			Node _parent = otherNode.getParentNode();
			
			if (!isSameNode(_parent)) {
				
				throw new DOMException(DOMException.NOT_FOUND_ERR, NOT_FOUND_ERR_MESSAGE);
			}
			
			// validation successful
			return;
		}

		throw new DOMException(DOMException.NOT_SUPPORTED_ERR, NOT_SUPPORTED_ERR_MESSAGE);
	}
	
	protected void checkHierarchy(Node otherNode) throws DOMException {
		
		// we can only check DOMNodes
		if (otherNode instanceof DOMNode) {
	
			// verify that the other node is not this node
			if (isSameNode(otherNode)) {
				throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, HIERARCHY_REQUEST_ERR_MESSAGE_SAME_NODE);		
			}
			
			// verify that otherNode is not one of the
			// the ancestors of this node
			// (prevent circular relationships)
			Node _parent = getParentNode();
			while (_parent != null) {
				
				if (_parent.isSameNode(otherNode)) {
					throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, HIERARCHY_REQUEST_ERR_MESSAGE_ANCESTOR);
				}
				
				_parent = _parent.getParentNode();
			}
			
			// TODO: check hierarchy constraints imposed by the schema

			
			// validation sucessful
			return;
		}

		throw new DOMException(DOMException.NOT_SUPPORTED_ERR, NOT_SUPPORTED_ERR_MESSAGE);
	}
	
	protected void checkSameDocument(Node otherNode) throws DOMException {
		
		Document doc = getOwnerDocument();

		if (doc != null) {

			if (!doc.equals(otherNode.getOwnerDocument())) {

				throw new DOMException(DOMException.WRONG_DOCUMENT_ERR, WRONG_DOCUMENT_ERR_MESSAGE);
			}
		}
	}
	
	protected void checkWriteAccess() throws DOMException {
		
		if (!securityContext.isAllowed(this, Permission.write)) {
			
			throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR, NO_MODIFICATION_ALLOWED_MESSAGE);
		}
	}
	
	protected void checkReadAccess() throws DOMException {
		
		if (!securityContext.isAllowed(this, Permission.read)) {
			
			throw new DOMException(DOMException.INVALID_ACCESS_ERR, INVALID_ACCESS_ERR_MESSAGE);
		}
	}
	
	protected String indent(final int depth, final boolean newline) {

		StringBuilder indent = new StringBuilder();

		if (newline) {

			indent.append("\n");

		}

		for (int d = 0; d < depth; d++) {

			indent.append("  ");

		}

		return indent.toString();
	}

	protected java.lang.Object getReferencedProperty(SecurityContext securityContext, RenderContext renderContext, String refKey)
		throws FrameworkException {

		AbstractNode node = null;
		String pageId = renderContext.getPageId();
		String[] parts                   = refKey.split("[\\.]+");
		String referenceKey              = parts[parts.length - 1];
		PropertyKey pageIdProperty       = new StringProperty(pageId);
		PropertyKey referenceKeyProperty = new StringProperty(referenceKey);
		
		Page page = renderContext.getPage();
		String componentId = renderContext.getComponentId();
		AbstractNode viewComponent = renderContext.getViewComponent();
		
		

		// walk through template parts
		for (int i = 0; (i < parts.length); i++) {

			String part = parts[i];

			// special keyword "request"
			if ("request".equals(part.toLowerCase())) {

				HttpServletRequest request = securityContext.getRequest();

				if (request != null) {

					return StringUtils.defaultString(request.getParameter(referenceKey));
				}

			}

			// special keyword "component"
			if ("component".equals(part.toLowerCase())) {

				node = PageHelper.getNodeById(securityContext, componentId);

				continue;

			}

			// special keyword "resource"
			if ("resource".equals(part.toLowerCase())) {

				node = PageHelper.getNodeById(securityContext, pageId);

				continue;

			}

			// special keyword "page"
			if ("page".equals(part.toLowerCase())) {

				node = page;

				continue;

			}

			// special keyword "link"
			if ("link".equals(part.toLowerCase())) {

				for (AbstractRelationship rel : getRelationships(RelType.LINK, Direction.OUTGOING)) {

					node = rel.getEndNode();

					break;

				}

				continue;

			}

			// special keyword "parent"
			if ("parent".equals(part.toLowerCase())) {

				for (AbstractRelationship rel : getRelationships(RelType.CONTAINS, Direction.INCOMING)) {

					if (rel.getProperty(pageIdProperty) != null) {

						node = rel.getStartNode();

						break;

					}

				}

				continue;

			}

			// special keyword "owner"
			if ("owner".equals(part.toLowerCase())) {

				for (AbstractRelationship rel : getRelationships(RelType.OWNS, Direction.INCOMING)) {

					node = rel.getStartNode();

					break;

				}

				continue;

			}

			// special keyword "data"
			if ("data".equals(part.toLowerCase())) {

				node = viewComponent;

				continue;

			}

			// special keyword "root": Find containing page
			if ("root".equals(part.toLowerCase())) {

				List<Page> pages = PageHelper.getPages(securityContext, viewComponent);

				if (pages.isEmpty()) {

					continue;
				}

				node = pages.get(0);

				continue;

			}

			// special keyword "result_size"
			if ("result_size".equals(part.toLowerCase())) {

				Set<Page> pages = getResultPages(securityContext, (Page) page);

				if (!pages.isEmpty()) {

					return pages.size();
				}

				return 0;

			}

			// special keyword "rest_result"
			if ("rest_result".equals(part.toLowerCase())) {

				HttpServletRequest request = securityContext.getRequest();

				if (request != null) {

					return StringEscapeUtils.escapeJavaScript(StringUtils.replace(StringUtils.defaultString((String) request.getAttribute(HtmlServlet.REST_RESPONSE)), "\n", ""));
				}

				return 0;

			}

		}

		if (node != null) {

			return node.getProperty(referenceKeyProperty);
		}

		return null;

	}

	protected String getPropertyWithVariableReplacement(SecurityContext securityContext, RenderContext renderContext, PropertyKey<String> key) throws FrameworkException {

		return replaceVariables(securityContext, renderContext, super.getProperty(key));

	}

	protected String getPropertyWithVariableReplacement(RenderContext renderContext, PropertyKey<String> key) throws FrameworkException {

		HttpServletRequest request = renderContext.getRequest();

		if (securityContext.getRequest() == null) {

			securityContext.setRequest(request);
		}

		return replaceVariables(securityContext, renderContext, super.getProperty(key));

	}

	protected String replaceVariables(SecurityContext securityContext, RenderContext renderContext, String rawValue)
		throws FrameworkException {

		String value = null;

		if ((rawValue != null) && (rawValue instanceof String)) {

			value = (String) rawValue;

			// re-use matcher from previous calls
			Matcher matcher = threadLocalTemplateMatcher.get();

			matcher.reset(value);

			while (matcher.find()) {

				String group  = matcher.group();
				String source = group.substring(2, group.length() - 1);

				// fetch referenced property
				String partValue = extractFunctions(securityContext, renderContext, source);

				if (partValue != null) {

					value = value.replace(group, partValue);
				}

			}

		}

		return value;

	}

	protected String extractFunctions(SecurityContext securityContext, RenderContext renderContext, String source)
		throws FrameworkException {

		AbstractNode viewComponent = renderContext.getViewComponent();
		String pageId = renderContext.getPageId();
		
		// re-use matcher from previous calls
		Matcher functionMatcher = threadLocalFunctionMatcher.get();

		functionMatcher.reset(source);

		if (functionMatcher.matches()) {

			String viewComponentId            = viewComponent != null
				? viewComponent.getProperty(AbstractNode.uuid)
				: null;
			String functionGroup              = functionMatcher.group(1);
			String parameter                  = functionMatcher.group(2);
			String functionName               = functionGroup.substring(0, functionGroup.length());
			Function<String, String> function = functions.get(functionName);

			if (function != null) {

				// store thread "state" in function
				function.setDataId(viewComponentId);
				function.setPageId(pageId);

				if (parameter.contains(",")) {

					String[] parameters = split(parameter);
					String[] results    = new String[parameters.length];

					// collect results from comma-separated function parameter
					for (int i = 0; i < parameters.length; i++) {

						results[i] = extractFunctions(securityContext, renderContext, StringUtils.strip(parameters[i]));
					}

					return function.apply(results);

				} else {

					String result = extractFunctions(securityContext, renderContext, StringUtils.strip(parameter));

					return function.apply(new String[] { result });

				}
			}

		}

		// if any of the following conditions match, the literal source value is returned
		if (StringUtils.isNotBlank(source) && StringUtils.isNumeric(source)) {

			// return numeric value
			return source;
		} else if (source.startsWith("\"") && source.endsWith("\"")) {

			return source.substring(1, source.length() - 1);
		} else if (source.startsWith("'") && source.endsWith("'")) {

			return source.substring(1, source.length() - 1);
		} else {

			// return property key
			return convertValueForHtml(getReferencedProperty(securityContext, renderContext, source));
		}
	}

	/**
	 * Return (cached) result pages
	 *
	 * Search string is taken from SecurityContext's http request
	 * Given displayPage is substracted from search result (we don't want to return search result page in search results)
	 *
	 * @param securityContext
	 * @param displayPage
	 * @return
	 */
	protected Set<Page> getResultPages(final SecurityContext securityContext, final Page displayPage) {

		HttpServletRequest request = securityContext.getRequest();
		String search              = request.getParameter("search");

		if ((request == null) || StringUtils.isEmpty(search)) {

			return Collections.EMPTY_SET;
		}

		if (request != null) {

			resultPages = (Set<Page>) request.getAttribute("searchResults");

			if ((resultPages != null) && !resultPages.isEmpty()) {

				return resultPages;
			}

		}

		if (resultPages == null) {

			resultPages = new HashSet<Page>();
		}

		// fetch search results
		// List<GraphObject> results              = ((SearchResultView) startNode).getGraphObjects(request);
		List<SearchAttribute> searchAttributes = new LinkedList<SearchAttribute>();

		searchAttributes.add(Search.andContent(search));
		searchAttributes.add(Search.andExactType(Content.class.getSimpleName()));

		try {

			Result<Content> result = Services.command(SecurityContext.getSuperUserInstance(), SearchNodeCommand.class).execute(searchAttributes);
			for (Content content : result.getResults()) {

				resultPages.addAll(PageHelper.getPages(securityContext, content));
			}

			// Remove result page itself
			resultPages.remove((Page) displayPage);

		} catch (FrameworkException fe) {

			logger.log(Level.WARNING, "Error while searching in content", fe);

		}

		return resultPages;

	}

	protected String convertValueForHtml(java.lang.Object value) {

		if (value != null) {

			// TODO: do more intelligent conversion here
			return value.toString();
		}

		return null;

	}

	protected String[] split(String source) {

		ArrayList<String> tokens   = new ArrayList<String>(20);
		boolean inDoubleQuotes     = false;
		boolean inSingleQuotes     = false;
		int len                    = source.length();
		int level                  = 0;
		StringBuilder currentToken = new StringBuilder(len);

		for (int i = 0; i < len; i++) {

			char c = source.charAt(i);

			// do not strip away separators in nested functions!
			if ((level != 0) || (c != ',')) {

				currentToken.append(c);
			}

			switch (c) {

				case '(' :
					level++;

					break;

				case ')' :
					level--;

					break;

				case '"' :
					if (inDoubleQuotes) {

						inDoubleQuotes = false;

						level--;

					} else {

						inDoubleQuotes = true;

						level++;

					}

					break;

				case '\'' :
					if (inSingleQuotes) {

						inSingleQuotes = false;

						level--;

					} else {

						inSingleQuotes = true;

						level++;

					}

					break;

				case ',' :
					if (level == 0) {

						tokens.add(currentToken.toString().trim());
						currentToken.setLength(0);

					}

					break;

			}

		}

		if (currentToken.length() > 0) {

			tokens.add(currentToken.toString().trim());
		}

		return tokens.toArray(new String[0]);

	}

	protected void collectNodesByPredicate(Node startNode, StructrNodeList results, Predicate<Node> predicate, int depth, boolean stopOnFirstHit) {
		
		if (predicate.evaluate(securityContext, startNode)) {

			results.add(startNode);

			if (stopOnFirstHit) {

				return;
			}
		}

		NodeList _children = startNode.getChildNodes();
		if (_children != null) {
			
			int len = _children.getLength();
			for (int i=0; i<len; i++) {

				Node child = _children.item(i);

				collectNodesByPredicate(child, results, predicate, depth+1, stopOnFirstHit);
			}
		}
	}

	// ----- public methods -----
	public List<AbstractRelationship> getChildRelationships() {
		return treeManager.getChildRelationships(this);
	}

	// ----- interface org.w3c.dom.Node -----
	@Override
	public String getTextContent() throws DOMException {

		final StructrNodeList results     = new StructrNodeList();
		final TextCollector textCollector = new TextCollector();
		
		collectNodesByPredicate(this, results, textCollector, 0, false);
		
		return textCollector.getText();
	}

	@Override
	public void setTextContent(String textContent) throws DOMException {
		// TODO: implement?
	}
	
	@Override
	public Node getParentNode() {
		return getProperty(parent);
	}

	@Override
	public NodeList getChildNodes() {
		
		checkReadAccess();
		
		return new StructrNodeList(treeManager.getChildren(this));
	}

	@Override
	public Node getFirstChild() {
		return treeManager.getFirstChild(this);
	}

	@Override
	public Node getLastChild() {
		return treeManager.getLastChild(this);
	}

	@Override
	public Node getPreviousSibling() {
		return treeManager.getListManager().getPrevious(this);
	}

	@Override
	public Node getNextSibling() {
		return treeManager.getListManager().getNext(this);
	}

	@Override
	public Document getOwnerDocument() {
		return getProperty(page);
	}

	@Override
	public Node insertBefore(final Node newChild, final Node refChild) throws DOMException {

		// according to DOM spec, insertBefore with null refChild equals appendChild
		if (refChild == null) {
			
			return appendChild(newChild);
		}
		
		checkWriteAccess();
		
		checkSameDocument(newChild);
		checkSameDocument(refChild);
		
		checkHierarchy(newChild);
		checkHierarchy(refChild);
		
		try {
			
			if (newChild instanceof DocumentFragment) {
	
				// When inserting document fragments, we must take
				// care of the special case that the nodes already
				// have a NEXT_LIST_ENTRY relationship coming from
				// the document fragment, so we must first remove
				// the node from the document fragment and then
				// add it to the new parent.
				
				DocumentFragment fragment = (DocumentFragment)newChild;
				DOMNode currentChild      = (DOMNode)fragment.getFirstChild();
				
				while (currentChild != null) {
			
					// save next child in fragment list for later use
					DOMNode savedNextChild = treeManager.getListManager().getNext(currentChild);

					// remove child from document fragment
					treeManager.removeChild((DOMNode)fragment, currentChild);

					// insert child into new parent
					treeManager.insertBefore(this, currentChild, (DOMNode)refChild);					

					// next
					currentChild = savedNextChild;
				}
				
			} else {
			
				Node _parent = newChild.getParentNode();

				if (_parent != null && _parent instanceof DOMNode) {
					treeManager.removeChild((DOMNode) _parent, (DOMNode) newChild);
				}

				treeManager.insertBefore(this, (DOMNode)newChild, (DOMNode)refChild);
				
			}

		} catch (FrameworkException fex) {

			throw new DOMException(DOMException.INVALID_STATE_ERR, fex.toString());			
		}					

		return refChild;
	}

	@Override
	public Node replaceChild(final Node newChild, final Node oldChild) throws DOMException {

		checkWriteAccess();
		
		checkSameDocument(newChild);
		checkSameDocument(oldChild);
		
		checkHierarchy(newChild);
		checkHierarchy(oldChild);
		
		try {

			if (newChild instanceof DocumentFragment) {

				// When inserting document fragments, we must take
				// care of the special case that the nodes already
				// have a NEXT_LIST_ENTRY relationship coming from
				// the document fragment, so we must first remove
				// the node from the document fragment and then
				// add it to the new parent.
				
				// replace indirectly using insertBefore and remove
				DocumentFragment fragment = (DocumentFragment)newChild;
				DOMNode currentChild      = (DOMNode)fragment.getFirstChild();
				
				while (currentChild != null) {
			
					// save next child in fragment list for later use
					DOMNode savedNextChild = treeManager.getListManager().getNext(currentChild);
					
					// remove child from document fragment
					treeManager.removeChild((DOMNode)fragment, currentChild);
					
					// add child to new parent
					treeManager.insertBefore(this, currentChild, (DOMNode)oldChild);
					
					// next
					currentChild = savedNextChild;
				}
				
				// finally, remove reference element
				treeManager.removeChild(this, (DOMNode)oldChild);
				
			} else {
			
				Node _parent = newChild.getParentNode();

				if (_parent != null && _parent instanceof DOMNode) {
					treeManager.removeChild((DOMNode) _parent, (DOMNode) newChild);
				}

				// replace directly
				treeManager.replaceChild(this, (DOMNode)newChild, (DOMNode)oldChild);
			}

		} catch (FrameworkException fex) {

			throw new DOMException(DOMException.INVALID_STATE_ERR, fex.toString());			
		}					

		return oldChild;
	}

	@Override
	public Node removeChild(final Node node) throws DOMException {

		checkWriteAccess();
		checkSameDocument(node);
		checkIsChild(node);
		
		try {
			
			treeManager.removeChild(this, (DOMNode)node);

		} catch (FrameworkException fex) {

			throw new DOMException(DOMException.INVALID_STATE_ERR, fex.toString());			
		}					

		return node;
	}

	@Override
	public Node appendChild(final Node newChild) throws DOMException {

		checkWriteAccess();
		checkSameDocument(newChild);
		checkHierarchy(newChild);

		try {
			
			if (newChild instanceof DocumentFragment) {

				// When inserting document fragments, we must take
				// care of the special case that the nodes already
				// have a NEXT_LIST_ENTRY relationship coming from
				// the document fragment, so we must first remove
				// the node from the document fragment and then
				// add it to the new parent.
				

				// replace indirectly using insertBefore and remove
				DocumentFragment fragment = (DocumentFragment)newChild;
				DOMNode currentChild      = (DOMNode)fragment.getFirstChild();
				
				while (currentChild != null) {
			
					// save next child in fragment list for later use
					DOMNode savedNextChild = treeManager.getListManager().getNext(currentChild);
					
					// remove child from document fragment
					treeManager.removeChild((DOMNode)fragment, currentChild);

					// append child to new parent
					treeManager.appendChild(this, (DOMNode)currentChild);

					// next
					currentChild = savedNextChild;
				}
				
			} else {

				Node _parent = newChild.getParentNode();

				if (_parent != null && _parent instanceof DOMNode) {
					treeManager.removeChild((DOMNode) _parent, (DOMNode) newChild);
				}
			
				treeManager.appendChild(this, (DOMNode)newChild);
			}
			
		} catch (FrameworkException fex) {

			throw new DOMException(DOMException.INVALID_STATE_ERR, fex.toString());			
		}					

		return newChild;
	}

	@Override
	public boolean hasChildNodes() {
		return !getProperty(children).isEmpty();
	}

	@Override
	public Node cloneNode(boolean deep) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean isSupported(String string, String string1) {
		return false;
	}

	@Override
	public String getNamespaceURI() {
		return null; //return "http://www.w3.org/1999/xhtml";
	}

	@Override
	public String getPrefix() {
		return null;
	}

	@Override
	public void setPrefix(String prefix) throws DOMException {
	}

	@Override
	public String getBaseURI() {
		return null;
	}

	@Override
	public short compareDocumentPosition(Node node) throws DOMException {
		return 0;
	}

	@Override
	public boolean isSameNode(Node node) {

		if (node != null && node instanceof DOMNode) {

			String otherId = ((DOMNode)node).getProperty(GraphObject.uuid);
			String ourId   = getProperty(GraphObject.uuid);
			
			if (ourId != null && otherId != null && ourId.equals(otherId)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public String lookupPrefix(String string) {
		return null;
	}

	@Override
	public boolean isDefaultNamespace(String string) {
		return true;
	}

	@Override
	public String lookupNamespaceURI(String string) {
		return null;
	}

	@Override
	public boolean isEqualNode(Node node) {
		return equals(node);
	}

	@Override
	public Object getFeature(String string, String string1) {
		return null;
	}

	@Override
	public Object setUserData(String string, Object o, UserDataHandler udh) {
		return null;
	}

	@Override
	public Object getUserData(String string) {
		return null;
	}
	
	@Override
	public final void normalize() {
		
		try {

			Services.command(securityContext, TransactionCommand.class).execute(new StructrTransaction() {

				@Override
				public Object execute() throws FrameworkException {

					Document document = getOwnerDocument();
					if (document != null) {

						// merge adjacent text nodes until there is only one left
						Node child = getFirstChild();
						while (child != null) {

							if (child instanceof Text) {

								Node next = child.getNextSibling();
								if (next != null && next instanceof Text) {

									String text1 = child.getNodeValue();
									String text2 = next.getNodeValue();

									// create new text node
									Text newText = document.createTextNode(text1.concat(text2));

									removeChild(child);
									insertBefore(newText, next);
									removeChild(next);

									child = newText;

								} else {

									// advance to next node
									child = next;
								}

							} else {

								// advance to next node
								child = child.getNextSibling();

							}
						}

						// recursively normalize child nodes
						if (hasChildNodes()) {

							Node currentChild = getFirstChild();
							while (currentChild != null) {

								currentChild.normalize();
								currentChild = currentChild.getNextSibling();
							}
						}
					}
					
					return null;
				}
				
			});
			
		} catch (FrameworkException fex) {
			
			throw new DOMException(DOMException.INVALID_STATE_ERR, fex.getMessage());
		}
	}

	// ----- interface DOMAdoptable -----
	@Override
	public Node doAdopt(final Page _page) throws DOMException {
		
		if (_page != null) {
			
			try {
				
				setProperty(page, _page);
				
			} catch (FrameworkException fex) {
				
				throw new DOMException(DOMException.INVALID_STATE_ERR, fex.getMessage());
			}
		}
		
		return this;
	}

	// ----- nested classes -----
	protected static class TextCollector implements Predicate<Node> {
		
		private StringBuilder textBuffer = new StringBuilder(200);

		@Override
		public boolean evaluate(SecurityContext securityContext, Node... obj) {

			if (obj[0] instanceof Text) {
				textBuffer.append(((Text)obj[0]).getTextContent());
			}

			return false;
		}
		
		public String getText() {
			return textBuffer.toString();
		}
	}
	
	protected static class TagPredicate implements Predicate<Node> {

		private String tagName = null;
		
		public TagPredicate(String tagName) {
			this.tagName = tagName;
		}
		
		@Override
		public boolean evaluate(SecurityContext securityContext, Node... obj) {

			if (obj[0] instanceof DOMElement) {

				DOMElement elem = (DOMElement)obj[0];

				if (tagName.equals(elem.getProperty(DOMElement.tag))) {					
					return true;
				}
			}

			return false;
		}
	}
}
