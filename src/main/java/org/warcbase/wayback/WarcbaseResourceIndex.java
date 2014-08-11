package org.warcbase.wayback;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.httpclient.URIException;
import org.archive.util.ArchiveUtils;
import org.archive.util.io.RuntimeIOException;
import org.archive.util.iterator.CloseableIterator;
import org.archive.wayback.UrlCanonicalizer;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.SearchResult;
import org.archive.wayback.core.SearchResults;
import org.archive.wayback.core.UrlSearchResult;
import org.archive.wayback.core.UrlSearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.AccessControlException;
import org.archive.wayback.exception.BadQueryException;
import org.archive.wayback.exception.ResourceIndexNotAvailableException;
import org.archive.wayback.exception.ResourceNotInArchiveException;
import org.archive.wayback.resourceindex.LocalResourceIndex;
import org.archive.wayback.resourceindex.SearchResultSource;
import org.archive.wayback.resourceindex.UpdatableSearchResultSource;
import org.archive.wayback.resourceindex.adapters.CaptureToUrlSearchResultIterator;
import org.archive.wayback.resourceindex.filterfactory.AccessPointCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.AnnotatingCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.CaptureFilterGroup;
import org.archive.wayback.resourceindex.filterfactory.ClosestTrackingCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.CoreCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.ExclusionCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.FilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.QueryCaptureFilterGroupFactory;
import org.archive.wayback.resourceindex.filterfactory.WindowFilterGroup;
import org.archive.wayback.util.ObjectFilter;
import org.archive.wayback.util.ObjectFilterChain;
import org.archive.wayback.util.ObjectFilterIterator;
import org.archive.wayback.util.url.AggressiveUrlCanonicalizer;
import org.archive.wayback.webapp.PerfStats;

public class WarcbaseResourceIndex extends LocalResourceIndex {
  public final static int TYPE_REPLAY = 0;
  public final static int TYPE_CAPTURE = 1;
  public final static int TYPE_URL = 2;

  /**
   * maximum number of records to return
   */
  private final static int MAX_RECORDS = 1000;

  enum PerfStat {
    IndexLoad;
  }

  private int maxRecords = MAX_RECORDS;

  protected SearchResultSource source;

  private UrlCanonicalizer canonicalizer = null;

  private boolean dedupeRecords = false;

  private boolean timestampSearch = false;

  private boolean markPrefixQueries = false;

  private ObjectFilter<CaptureSearchResult> annotater = null;

  private ObjectFilter<CaptureSearchResult> filter = null;

  protected List<FilterGroupFactory> fgFactories = null;

  public WarcbaseResourceIndex() {
    canonicalizer = new AggressiveUrlCanonicalizer();
    fgFactories = new ArrayList<FilterGroupFactory>();
    fgFactories.add(new AccessPointCaptureFilterGroupFactory());
    fgFactories.add(new CoreCaptureFilterGroupFactory());
    fgFactories.add(new QueryCaptureFilterGroupFactory());
    fgFactories.add(new AnnotatingCaptureFilterGroupFactory());
    fgFactories.add(new ExclusionCaptureFilterGroupFactory());
    fgFactories.add(new ClosestTrackingCaptureFilterGroupFactory());
  }

  private void cleanupIterator(CloseableIterator<? extends SearchResult> itr)
      throws ResourceIndexNotAvailableException {
    try {
      itr.close();
    } catch (IOException e) {
      e.printStackTrace();
      throw new ResourceIndexNotAvailableException(e.getLocalizedMessage());
    }
  }

  protected List<CaptureFilterGroup> getRequestFilterGroups(WaybackRequest r)
      throws BadQueryException {

    ArrayList<CaptureFilterGroup> groups = new ArrayList<CaptureFilterGroup>();
    for (FilterGroupFactory f : fgFactories) {
      groups.add(f.getGroup(r, canonicalizer, this));
    }
    return groups;
  }

  public CaptureSearchResults doCaptureQuery(WaybackRequest wbRequest, int type)
      throws ResourceIndexNotAvailableException, ResourceNotInArchiveException, BadQueryException,
      AccessControlException {

    wbRequest.setResultsPerPage(100);
    String urlKey;
    try {
      urlKey = canonicalizer.urlStringToKey(wbRequest.getRequestUrl());
    } catch (IOException e) {
      throw new BadQueryException("Bad URL(" + wbRequest.getRequestUrl() + ")");
    }

    // Special handling for index where the key is url<space>timestamp
    // for faster binary search lookup
    if (timestampSearch && wbRequest.isTimestampSearchKey()) {
      String replayTimestamp = wbRequest.getReplayTimestamp();

      if (replayTimestamp != null) {
        urlKey += " " + replayTimestamp;
      }
    }

    System.out.println(">>> Searching for " + urlKey + " " + wbRequest.getReplayTimestamp());

    CaptureSearchResults results = new CaptureSearchResults();
    ObjectFilterChain<CaptureSearchResult> filters = new ObjectFilterChain<CaptureSearchResult>();

    WindowFilterGroup<CaptureSearchResult> window =
        new WindowFilterGroup<CaptureSearchResult>(wbRequest, this);
    List<CaptureFilterGroup> groups = getRequestFilterGroups(wbRequest);
    if (filter != null) {
      filters.addFilter(filter);
    }

    for (CaptureFilterGroup cfg : groups) {
      filters.addFilters(cfg.getFilters());
    }
    filters.addFilters(window.getFilters());

    CloseableIterator<CaptureSearchResult> itr = null;
    try {
      itr = new ObjectFilterIterator<CaptureSearchResult>(
          getIterator(wbRequest.getRequestUrl(), urlKey), filters);

      while (itr.hasNext()) {
        results.addSearchResult(itr.next());
      }
    } catch (RuntimeIOException e) {
    }

    for (CaptureFilterGroup cfg : groups) {
      cfg.annotateResults(results);
    }

    window.annotateResults(results);

    for (CaptureSearchResult r : results) {
      System.out.println(">>> " + r.getOriginalUrl() + " " + r.getCaptureTimestamp());
    }
    return results;
  }

  public CloseableIterator<CaptureSearchResult> getIterator(final String url, final String urlKey)
      throws ResourceIndexNotAvailableException {

    final String resourceUrl = "http://nest.umiacs.umd.edu:8080/arc.sample.raw/*/" + url;
    System.out.println(">>> fetching resource url: " + resourceUrl);
    List<String> lines = null;
    try {
      lines = Arrays.asList(new String(WarcbaseResourceStore.getAsByteArray(new URL(resourceUrl)))
          .split("\n"));
    } catch (MalformedURLException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
    final Iterator<String> it = lines.iterator();

    return new CloseableIterator<CaptureSearchResult>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public CaptureSearchResult next() {
        String line = it.next();
        System.out.println(">>>!!! " + line);
        String[] splits = line.split("\\s+");
        CaptureSearchResult r = new CaptureSearchResult();
        try {
          r.setCaptureDate(ArchiveUtils.parse14DigitDate(splits[0]));
        } catch (ParseException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        r.setOriginalUrl(url);
        r.setUrlKey(urlKey);
        // doesn't matter, or we get NPE
        r.setMimeType(splits[1]);
        r.setFile("foo");
        // needed, or otherwise we'll get a NPE in CalendarResults.jsp
        r.setRedirectUrl("-");
        r.setHttpCode("200");
        r.setOffset(0);
        return r;
      }

      @Override public void remove() {}

      @Override public void close() throws IOException {}
    };
  }

  public UrlSearchResults doUrlQuery(WaybackRequest wbRequest)
      throws ResourceIndexNotAvailableException, ResourceNotInArchiveException, BadQueryException,
      AccessControlException {

    String urlKey;
    try {
      urlKey = canonicalizer.urlStringToKey(wbRequest.getRequestUrl());
    } catch (URIException e) {
      throw new BadQueryException("Bad URL(" + wbRequest.getRequestUrl() + ")");
    }

    if (markPrefixQueries) {
      urlKey += "*\t";
    }

    UrlSearchResults results = new UrlSearchResults();

    // the various CAPTURE filters to apply to the results:
    ObjectFilterChain<CaptureSearchResult> cFilters = new ObjectFilterChain<CaptureSearchResult>();

    // Groupings of filters for clarity(?) and summary annotation of
    // results:
    List<CaptureFilterGroup> groups = getRequestFilterGroups(wbRequest);
    for (CaptureFilterGroup cfg : groups) {
      cFilters.addFilters(cfg.getFilters());
    }
    if (filter != null) {
      cFilters.addFilter(filter);
    }

    // we've filtered the appropriate CaptureResult objects within the
    // iterator, now we're going to convert whatever records make it past
    // the filters into UrlSearchResults, and then do further window
    // filtering on those results:
    // Windows:
    // the window URL filters to apply to the results, once they're
    // UrlSearchResult objects
    ObjectFilterChain<UrlSearchResult> uFilters = new ObjectFilterChain<UrlSearchResult>();
    WindowFilterGroup<UrlSearchResult> window = new WindowFilterGroup<UrlSearchResult>(wbRequest,
        this);
    uFilters.addFilters(window.getFilters());

    CloseableIterator<CaptureSearchResult> itrC = null;
    CloseableIterator<UrlSearchResult> itrU = null;

    try {
      PerfStats.timeStart(PerfStat.IndexLoad);

      itrC = new ObjectFilterIterator<CaptureSearchResult>(source.getPrefixIterator(urlKey),
          cFilters);

      itrU = new ObjectFilterIterator<UrlSearchResult>(new CaptureToUrlSearchResultIterator(itrC),
          uFilters);

      while (itrU.hasNext()) {
        results.addSearchResult(itrU.next());
      }
    } finally {
      if (itrU != null) {
        cleanupIterator(itrU);
      }
      PerfStats.timeEnd(PerfStat.IndexLoad);
    }

    for (CaptureFilterGroup cfg : groups) {
      cfg.annotateResults(results);
    }
    window.annotateResults(results);

    return results;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.archive.wayback.ResourceIndex#query(org.archive.wayback.core.WaybackRequest)
   */
  public SearchResults query(WaybackRequest wbRequest) throws ResourceIndexNotAvailableException,
      ResourceNotInArchiveException, BadQueryException, AccessControlException {
    SearchResults results = null; // return value placeholder

    if (wbRequest.isReplayRequest()) {

      results = doCaptureQuery(wbRequest, TYPE_REPLAY);
      results.putFilter(WaybackRequest.REQUEST_TYPE, WaybackRequest.REQUEST_REPLAY_QUERY);

    } else if (wbRequest.isCaptureQueryRequest()) {

      results = doCaptureQuery(wbRequest, TYPE_CAPTURE);
      results.putFilter(WaybackRequest.REQUEST_TYPE, WaybackRequest.REQUEST_CAPTURE_QUERY);

    } else if (wbRequest.isUrlQueryRequest()) {

      results = doUrlQuery(wbRequest);
      results.putFilter(WaybackRequest.REQUEST_TYPE, WaybackRequest.REQUEST_URL_QUERY);

    } else {

      throw new BadQueryException("Unknown query type, must be "
          + WaybackRequest.REQUEST_REPLAY_QUERY + ", " + WaybackRequest.REQUEST_CAPTURE_QUERY
          + ", or " + WaybackRequest.REQUEST_URL_QUERY);
    }
    return results;
  }

  public void addSearchResults(Iterator<CaptureSearchResult> itr) throws IOException,
      UnsupportedOperationException {
    if (source instanceof UpdatableSearchResultSource) {
      UpdatableSearchResultSource updatable = (UpdatableSearchResultSource) source;
      updatable.addSearchResults(itr, canonicalizer);
    } else {
      throw new UnsupportedOperationException("Underlying "
          + "SearchResultSource is not Updatable.");
    }
  }

  public boolean isUpdatable() {
    return (source instanceof UpdatableSearchResultSource);
  }

  /**
   * @param maxRecords the maxRecords to set
   */
  public void setMaxRecords(int maxRecords) {
    this.maxRecords = maxRecords;
  }

  public int getMaxRecords() {
    return maxRecords;
  }

  /**
   * @param source the source to set
   */
  public void setSource(SearchResultSource source) {
    this.source = source;
  }

  public boolean isDedupeRecords() {
    return dedupeRecords;
  }

  public void setDedupeRecords(boolean dedupeRecords) {
    this.dedupeRecords = dedupeRecords;
  }

  public UrlCanonicalizer getCanonicalizer() {
    return canonicalizer;
  }

  public void setCanonicalizer(UrlCanonicalizer canonicalizer) {
    this.canonicalizer = canonicalizer;
  }

  public void shutdown() throws IOException {
    source.shutdown();
  }

  public ObjectFilter<CaptureSearchResult> getAnnotater() {
    return annotater;
  }

  public void setAnnotater(ObjectFilter<CaptureSearchResult> annotater) {
    this.annotater = annotater;
  }

  public ObjectFilter<CaptureSearchResult> getFilter() {
    return filter;
  }

  public void setFilter(ObjectFilter<CaptureSearchResult> filter) {
    this.filter = filter;
  }

  public boolean isTimestampSearch() {
    return timestampSearch;
  }

  public void setTimestampSearch(boolean timestampSearch) {
    this.timestampSearch = timestampSearch;
  }

  public boolean isMarkPrefixQueries() {
    return markPrefixQueries;
  }

  public void setMarkPrefixQueries(boolean markPrefixQueries) {
    this.markPrefixQueries = markPrefixQueries;
  }
}
