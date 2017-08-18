/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.brackit.xquery.xdm.DocumentException;
import org.sirix.access.conf.ResourceConfiguration;
import org.sirix.api.PageReadTrx;
import org.sirix.api.ResourceManager;
import org.sirix.cache.BufferManager;
import org.sirix.cache.IndexLogKey;
import org.sirix.cache.IndirectPageLogKey;
import org.sirix.cache.RecordPageContainer;
import org.sirix.cache.TransactionIndexLogCache;
import org.sirix.cache.TransactionLogPageCache;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Reader;
import org.sirix.node.DeletedNode;
import org.sirix.node.Kind;
import org.sirix.node.interfaces.Record;
import org.sirix.page.CASPage;
import org.sirix.page.IndirectPage;
import org.sirix.page.NamePage;
import org.sirix.page.PageKind;
import org.sirix.page.PageReference;
import org.sirix.page.PathPage;
import org.sirix.page.PathSummaryPage;
import org.sirix.page.RevisionRootPage;
import org.sirix.page.UberPage;
import org.sirix.page.UnorderedKeyValuePage;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.page.interfaces.Page;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import org.sirix.settings.Versioning;

import com.google.common.base.MoreObjects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * <h1>PageReadTransaction</h1>
 *
 * <p>
 * Page reading transaction. The only thing shared amongst transactions is the session. Everything
 * else is exclusive to this transaction. It is required that only a single thread has access to
 * this transaction.
 * </p>
 */
final class PageReadTrxImpl implements PageReadTrx {

	/** Page reader exclusively assigned to this transaction. */
	private final Reader mPageReader;

	/** Uber page this transaction is bound to. */
	private final UberPage mUberPage;

	/** Cached name page of this revision. */
	private final RevisionRootPage mRootPage;

	/** Internal reference to node cache. */
	private final LoadingCache<IndexLogKey, RecordPageContainer<UnorderedKeyValuePage>> mNodeCache;

	/** Internal reference to page cache. */
	private final LoadingCache<PageReference, Page> mPageCache;

	/** {@link XdmResourceManager} reference. */
	protected final XdmResourceManager mResourceManager;

	/** {@link NamePage} reference. */
	private final NamePage mNamePage;

	/** Determines if page reading transaction is closed or not. */
	private boolean mClosed;

	/**
	 * Optional page transaction log, dependent on the fact, if the log hasn't been completely
	 * transferred into the data file.
	 */
	private final Optional<TransactionLogPageCache> mPageLog;

	/**
	 * Optional node transaction log, dependent on the fact, if the log hasn't been completely
	 * transferred into the data file.
	 */
	private final Optional<TransactionIndexLogCache<UnorderedKeyValuePage>> mNodeLog;

	/** {@link ResourceConfiguration} instance. */
	final ResourceConfiguration mResourceConfig;

	/** Optional {@link PageWriteTrxImpl} needed for very first revision. */
	private final Optional<PageWriteTrxImpl> mPageWriteTrx;

	/** {@link IndexController} instance. */
	private final IndexController mIndexController;

	/** Caches in-memory reconstructed pages of a specific resource. */
	private final BufferManager mResourceBufferManager;

	/**
	 * Standard constructor.
	 *
	 * @param session current {@link XdmResourceManager} instance
	 * @param uberPage {@link UberPage} to start reading from
	 * @param revision key of revision to read from uber page
	 * @param reader reader to read stored pages for this transaction
	 * @param pageWriteLog optional page cache
	 * @param pageCache caches in-memory reconstructed pages of a specific resource.
	 * @param unorderedKeyValuePageWriteLog optional key/value page cache
	 * @throws SirixIOException if reading of the persistent storage fails
	 */
	PageReadTrxImpl(final XdmResourceManager session, final UberPage uberPage,
			final @Nonnegative int revision, final Reader reader,
			final Optional<PageWriteTrxImpl> pageWriteTrx,
			final Optional<IndexController> indexController, final @Nonnull BufferManager bufferManager)
			throws SirixIOException {
		checkArgument(revision >= 0, "Revision must be >= 0!");
		mResourceBufferManager = requireNonNull(bufferManager);
		mPageWriteTrx = checkNotNull(pageWriteTrx);
		mClosed = false;
		mResourceConfig = session.getResourceConfig();
		mIndexController = indexController.isPresent() ? indexController.get()
				: session.getRtxIndexController(revision);

		if (!indexController.isPresent()) {
			// Deserialize index definitions.
			final File indexes = new File(session.getResourceConfig().mPath,
					ResourceConfiguration.Paths.INDEXES.getFile().getPath() + revision + ".xml");
			if (indexes.exists()) {
				try (final InputStream in = new FileInputStream(indexes)) {
					mIndexController.getIndexes().init(mIndexController.deserialize(in).getFirstChild());
				} catch (IOException | DocumentException | SirixException e) {
					throw new SirixIOException("Index definitions couldn't be deserialized!", e);
				}
			}
		}

		final File commitFile = session.commitFile();
		final boolean doesExist = commitFile.exists();

		mResourceManager = checkNotNull(session);
		mPageReader = checkNotNull(reader);
		mUberPage = checkNotNull(uberPage);

		// Transaction logs which might have to be read because the data hasn't been
		// commited to the data-file.
		// =======================================================
		mPageLog = doesExist
				? Optional.of(new TransactionLogPageCache(session.getResourceConfig().mPath, "page", this))
				: Optional.empty();
		mNodeLog = doesExist
				? Optional
						.of(new TransactionIndexLogCache<>(session.getResourceConfig().mPath, "node", this))
				: Optional.empty();

		// In memory caches from data directory.
		// =========================================================
		final PageReadTrxImpl pageReadTrx = this;
		mNodeCache = CacheBuilder.newBuilder().maximumSize(10_000)
				.expireAfterWrite(5_000, TimeUnit.SECONDS).expireAfterAccess(5_000, TimeUnit.SECONDS)
				.build(new CacheLoader<IndexLogKey, RecordPageContainer<UnorderedKeyValuePage>>() {
					@Override
					public RecordPageContainer<UnorderedKeyValuePage> load(final IndexLogKey key) {
						final RecordPageContainer<UnorderedKeyValuePage> container =
								mNodeLog.isPresent() ? mNodeLog.get().get(key)
										: RecordPageContainer.emptyInstance();
						return container.equals(RecordPageContainer.EMPTY_INSTANCE)
								? pageReadTrx.getRecordPageContainer(key.getRecordPageKey(), key.getIndex(),
										key.getIndexType())
								: container;
					}
				});

		final CacheBuilder<Object, Object> pageCacheBuilder = CacheBuilder.newBuilder();
		mPageCache = pageCacheBuilder.build(new CacheLoader<PageReference, Page>() {
			@Override
			public Page load(final PageReference reference) {
				Page page =
						mPageLog.isPresent() ? mPageLog.get().get(reference.getLogKey()) : reference.getPage();
				if (page == null) {
					page = mPageReader.read(reference.getKey(), pageReadTrx).setDirty(true);

					if (page != null && !mPageWriteTrx.isPresent()) {
						// Put page into buffer manager and set page reference (just to
						// track when the in-memory page must be removed).
						mResourceBufferManager.getPageCache().put(reference, page);
						reference.setPage(page);
					}
				}
				return page;
			}
		});

		// Load revision root.
		mRootPage = loadRevRoot(revision);
		assert mRootPage != null : "root page must not be null!";
		mNamePage = getNamePage(mRootPage);
	}

	@Override
	public ResourceManager getSession() {
		assertNotClosed();
		return mResourceManager;
	}

	/**
	 * Make sure that the transaction is not yet closed when calling this method.
	 */
	final void assertNotClosed() {
		if (mClosed) {
			throw new IllegalStateException("Transaction is already closed.");
		}
	}

	@Override
	public Optional<Record> getRecord(final long nodeKey, final PageKind pageKind,
			final @Nonnegative int index) {
		checkNotNull(pageKind);
		assertNotClosed();

		if (nodeKey == Fixed.NULL_NODE_KEY.getStandardProperty()) {
			return Optional.empty();
		}

		final long recordPageKey = pageKey(nodeKey);

		final RecordPageContainer<UnorderedKeyValuePage> cont;

		try {
			switch (pageKind) {
				case RECORDPAGE:
				case PATHSUMMARYPAGE:
				case PATHPAGE:
				case CASPAGE:
				case NAMEPAGE:
					cont = mNodeCache.get(new IndexLogKey(pageKind, recordPageKey, index));
					break;
				default:
					throw new IllegalStateException();
			}
		} catch (final ExecutionException | UncheckedExecutionException e) {
			throw new SirixIOException(e.getCause());
		}

		if (cont.equals(RecordPageContainer.EMPTY_INSTANCE)) {
			return Optional.empty();
		}

		final Record retVal = cont.getComplete().getValue(nodeKey);
		return checkItemIfDeleted(retVal);
	}

	/**
	 * Method to check if an {@link Record} is deleted.
	 *
	 * @param toCheck node to check
	 * @return the {@code node} if it is valid, {@code null} otherwise
	 */
	final Optional<Record> checkItemIfDeleted(final @Nullable Record toCheck) {
		if (toCheck instanceof DeletedNode) {
			return Optional.empty();
		} else {
			return Optional.ofNullable(toCheck);
		}
	}

	@Override
	public String getName(final int nameKey, final Kind nodeKind) {
		assertNotClosed();
		return mNamePage.getName(nameKey, nodeKind);
	}

	@Override
	public final byte[] getRawName(final int pNameKey, final Kind pNodeKind) {
		assertNotClosed();
		return mNamePage.getRawName(pNameKey, pNodeKind);
	}

	@Override
	public void clearCaches() {
		assertNotClosed();
		mNodeCache.invalidateAll();
		mPageCache.invalidateAll();

		if (mNodeLog.isPresent()) {
			mNodeLog.get().clear();
		}
		if (mPageLog.isPresent()) {
			mPageLog.get().clear();
		}
	}

	@Override
	public void closeCaches() {
		assertNotClosed();
		if (mNodeLog.isPresent()) {
			mNodeLog.get().close();
		}
		if (mPageLog.isPresent()) {
			mPageLog.get().close();
		}
	}

	/**
	 * Get revision root page belonging to revision key.
	 *
	 * @param revisionKey key of revision to find revision root page for
	 * @return revision root page of this revision key
	 *
	 * @throws SirixIOException if something odd happens within the creation process
	 */
	final RevisionRootPage loadRevRoot(final @Nonnegative int revisionKey) throws SirixIOException {
		checkArgument(revisionKey >= 0 && revisionKey <= mResourceManager.getMostRecentRevisionNumber(),
				"%s must be >= 0 and <= last stored revision (%s)!", revisionKey,
				mResourceManager.getMostRecentRevisionNumber());

		// The indirect page reference either fails horribly or returns a non null
		// instance.
		final PageReference reference = getPageReferenceForPage(mUberPage.getIndirectPageReference(),
				revisionKey, -1, PageKind.UBERPAGE);
		try {
			RevisionRootPage page = null;
			if (mPageWriteTrx.isPresent()) {
				page = (RevisionRootPage) mPageWriteTrx.get().mPageLog.get(reference.getLogKey());
			}
			if (page == null) {
				assert reference.getKey() != Constants.NULL_ID || reference.getLogKey() != null;
				page = (RevisionRootPage) mPageCache.get(reference);
			}
			return page;
		} catch (final ExecutionException | UncheckedExecutionException e) {
			throw new SirixIOException(e.getCause());
		}
	}

	@Override
	public final NamePage getNamePage(final RevisionRootPage revisionRoot) throws SirixIOException {
		assertNotClosed();
		return (NamePage) getPage(revisionRoot.getNamePageReference(), PageKind.NAMEPAGE);
	}

	@Override
	public final PathSummaryPage getPathSummaryPage(final RevisionRootPage revisionRoot)
			throws SirixIOException {
		assertNotClosed();
		return (PathSummaryPage) getPage(revisionRoot.getPathSummaryPageReference(),
				PageKind.PATHSUMMARYPAGE);
	}

	@Override
	public final PathPage getPathPage(final RevisionRootPage revisionRoot) throws SirixIOException {
		assertNotClosed();
		return (PathPage) getPage(revisionRoot.getPathPageReference(), PageKind.PATHPAGE);
	}

	@Override
	public final CASPage getCASPage(final RevisionRootPage revisionRoot) throws SirixIOException {
		assertNotClosed();
		return (CASPage) getPage(revisionRoot.getCASPageReference(), PageKind.CASPAGE);
	}

	/**
	 * Set the page if it is not set already.
	 *
	 * @param reference page reference
	 * @throws SirixIOException if an I/O error occurs
	 */
	private Page getPage(final PageReference reference, final PageKind pageKind)
			throws SirixIOException {
		try {
			Page page = reference.getPage();

			if (mPageWriteTrx.isPresent() || mPageLog.isPresent()) {
				final IndirectPageLogKey logKey = new IndirectPageLogKey(pageKind, -1, -1, 0);
				reference.setLogKey(logKey);
			}

			if (page == null) {
				page = mPageCache.get(reference);
				reference.setPage(page);
			}

			return page;
		} catch (final ExecutionException | UncheckedExecutionException e) {
			throw new SirixIOException(e.getCause());
		}
	}

	@Override
	public final UberPage getUberPage() {
		assertNotClosed();
		return mUberPage;
	}

	@Override
	public <K extends Comparable<? super K>, V extends Record, S extends KeyValuePage<K, V>> RecordPageContainer<S> getRecordPageContainer(
			final @Nonnegative Long recordPageKey, final int index, final PageKind pageKind)
			throws SirixIOException {
		assertNotClosed();
		checkArgument(recordPageKey >= 0, "recordPageKey must not be negative!");

		final Optional<PageReference> pageReferenceToRecordPage =
				getLeafPageReference(checkNotNull(recordPageKey), index, checkNotNull(pageKind));

		if (!pageReferenceToRecordPage.isPresent()) {
			return RecordPageContainer.emptyInstance();
		}

		// Try to get from resource buffer manager.
		@SuppressWarnings("unchecked")
		final RecordPageContainer<S> recordPageContainerFromBuffer =
				(RecordPageContainer<S>) mResourceBufferManager.getRecordPageCache()
						.get(pageReferenceToRecordPage.get());

		if (recordPageContainerFromBuffer != null) {
			return recordPageContainerFromBuffer;
		}

		// Load list of page "fragments" from persistent storage.
		final List<S> pages = getSnapshotPages(pageReferenceToRecordPage.get());

		if (pages.isEmpty()) {
			return RecordPageContainer.emptyInstance();
		}

		final int mileStoneRevision = mResourceConfig.mRevisionsToRestore;
		final Versioning revisioning = mResourceConfig.mRevisionKind;
		final S completePage = revisioning.combineRecordPages(pages, mileStoneRevision, this);

		final RecordPageContainer<S> recordPageContainer = new RecordPageContainer<S>(completePage);

		// recordPageContainerFromBufferesource buffer manager.
		if (!mPageWriteTrx.isPresent())
			mResourceBufferManager.getRecordPageCache().put(pageReferenceToRecordPage.get(),
					recordPageContainer);

		return recordPageContainer;
	}

	final Optional<PageReference> getLeafPageReference(final @Nonnegative long recordPageKey,
			final int index, final PageKind pageKind) {
		final PageReference tmpRef = getPageReference(mRootPage, pageKind, index);
		return Optional.ofNullable(getPageReferenceForPage(tmpRef, recordPageKey, index, pageKind));
	}

	/**
	 * Dereference key/value page reference and get all leaves, the {@link KeyValuePage}s from the
	 * revision-trees.
	 *
	 * @param recordPageKey key of node page
	 * @param pageKind kind of page, that is the type of tree to dereference
	 * @param index index number or {@code -1}, if it's a regular record page
	 * @param pageReference optional page reference pointing to the first page
	 * @return dereferenced pages
	 *
	 * @throws SirixIOException if an I/O-error occurs within the creation process
	 */
	final <K extends Comparable<? super K>, V extends Record, S extends KeyValuePage<K, V>> List<S> getSnapshotPages(
			final PageReference pageReference) {
		assert pageReference != null;
		final ResourceConfiguration config = mResourceManager.getResourceConfig();
		final int revsToRestore = config.mRevisionsToRestore;
		final int[] revisionsToRead =
				config.mRevisionKind.getRevisionRoots(mRootPage.getRevision(), revsToRestore);
		final List<S> pages = new ArrayList<>(revisionsToRead.length);
		boolean first = true;
		for (int i = 0; i < revisionsToRead.length; i++) {
			Optional<PageReference> refToRecordPage = null;
			if (first) {
				first = false;
				refToRecordPage = Optional.of(pageReference);
			} else {
				refToRecordPage = pages.get(pages.size() - 1).getPreviousReference();
			}

			if (refToRecordPage.isPresent()) {
				final PageReference reference = refToRecordPage.get();
				if (reference.getKey() != Constants.NULL_ID) {
					@SuppressWarnings("unchecked")
					final S page = (S) mPageReader.read(reference.getKey(), this);
					pages.add(page);
					if (page.size() == Constants.NDP_NODE_COUNT) {
						// Page is full, thus we can skip reconstructing pages with elder
						// versions.
						break;
					}
				}
			} else {
				break;
			}
		}
		return pages;
	}

	/**
	 * Get the page reference which points to the right subtree (nodes, path summary nodes, CAS index
	 * nodes, Path index nodes or Name index nodes).
	 *
	 * @param revisionRoot {@link RevisionRootPage} instance
	 * @param pageKind the page kind to determine the right subtree
	 */
	PageReference getPageReference(final RevisionRootPage revisionRoot, final PageKind pageKind,
			final int index) throws SirixIOException {
		assert revisionRoot != null;
		PageReference ref = null;
		switch (pageKind) {
			case RECORDPAGE:
				ref = revisionRoot.getIndirectPageReference();
				break;
			case CASPAGE:
				ref = getCASPage(revisionRoot).getIndirectPageReference(index);
				break;
			case PATHPAGE:
				ref = getPathPage(revisionRoot).getIndirectPageReference(index);
				break;
			case NAMEPAGE:
				ref = getNamePage(revisionRoot).getIndirectPageReference(index);
				break;
			case PATHSUMMARYPAGE:
				ref = getPathSummaryPage(revisionRoot).getIndirectPageReference(index);
				break;
			default:
				throw new IllegalStateException(
						"Only defined for node, path summary, text value and attribute value pages!");
		}
		return ref;
	}

	/**
	 * Dereference indirect page reference.
	 *
	 * @param reference reference to dereference
	 * @return dereferenced page
	 *
	 * @throws SirixIOException if something odd happens within the creation process
	 * @throws NullPointerException if {@code reference} is {@code null}
	 */
	final IndirectPage dereferenceIndirectPage(final PageReference reference) {
		try {
			IndirectPage page = null;

			if (mPageWriteTrx.isPresent()) {
				page = (IndirectPage) mPageWriteTrx.get().mPageLog.get(reference.getLogKey());
			}

			if (page == null) {
				page = (IndirectPage) reference.getPage();
			}

			if (page == null
					&& (reference.getKey() != Constants.NULL_ID || reference.getLogKey() != null)) {
				page = (IndirectPage) mPageCache.get(reference);
			}

			return page;
		} catch (final ExecutionException | UncheckedExecutionException e) {
			throw new SirixIOException(e.getCause());
		}
	}

	/**
	 * Find reference pointing to leaf page of an indirect tree.
	 *
	 * @param startReference start reference pointing to the indirect tree
	 * @param key key to look up in the indirect tree
	 * @return reference denoted by key pointing to the leaf page
	 *
	 * @throws SirixIOException if an I/O error occurs
	 */
	@Nullable
	@Override
	public final PageReference getPageReferenceForPage(final PageReference startReference,
			final @Nonnegative long key, final int index, final PageKind pageKind)
			throws SirixIOException {
		assertNotClosed();

		// Initial state pointing to the indirect page of level 0.
		PageReference reference = checkNotNull(startReference);
		checkArgument(key >= 0, "key must be >= 0!");
		checkNotNull(pageKind);
		int offset = 0;
		int parentOffset = 0;
		long levelKey = key;
		final int[] inpLevelPageCountExp = mUberPage.getPageCountExp(pageKind);

		// Iterate through all levels.
		for (int level = 0, height = inpLevelPageCountExp.length; level < height; level++) {
			offset = (int) (levelKey >> inpLevelPageCountExp[level]);
			levelKey -= offset << inpLevelPageCountExp[level];
			if (reference.getLogKey() == null) {
				// && (mPageWriteTrx.isPresent() || mPageLog.isPresent())) {
				reference.setLogKey(new IndirectPageLogKey(pageKind, index, level,
						parentOffset * Constants.INP_REFERENCE_COUNT + offset));
			}
			final Page derefPage = dereferenceIndirectPage(reference);
			if (derefPage == null) {
				reference = null;
				break;
			} else {
				try {
					reference = derefPage.getReference(offset);
				} catch (final IndexOutOfBoundsException e) {
					throw new SirixIOException("Node key isn't supported, it's too big!");
				}
			}
			parentOffset = offset;
		}

		// Return reference to leaf of indirect tree.
		if (reference != null && reference.getLogKey() == null) {
			// && (mPageWriteTrx.isPresent() || mPageLog.isPresent())) {
			reference.setLogKey(new IndirectPageLogKey(pageKind, index, inpLevelPageCountExp.length,
					parentOffset * Constants.INP_REFERENCE_COUNT + offset));
		}
		return reference;
	}

	@Override
	public long pageKey(final @Nonnegative long recordKey) {
		assertNotClosed();
		checkArgument(recordKey >= 0, "recordKey must not be negative!");
		return recordKey >> Constants.NDP_NODE_COUNT_EXPONENT;
	}

	@Override
	public RevisionRootPage getActualRevisionRootPage() {
		assertNotClosed();
		return mRootPage;
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this).add("Session", mResourceManager)
				.add("PageReader", mPageReader).add("UberPage", mUberPage).add("RevRootPage", mRootPage)
				.toString();
	}

	@Override
	public void close() {
		if (!mClosed) {
			closeCaches();
			mPageReader.close();

			mClosed = true;
		}
	}

	@Override
	public int getNameCount(int key, @Nonnull Kind kind) {
		assertNotClosed();
		return mNamePage.getCount(key, kind);
	}

	@Override
	public boolean isClosed() {
		return mClosed;
	}

	@Override
	public int getRevisionNumber() {
		assertNotClosed();
		return mRootPage.getRevision();
	}

	@Override
	public Reader getReader() {
		return mPageReader;
	}
}
