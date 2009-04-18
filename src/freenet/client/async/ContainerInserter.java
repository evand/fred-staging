/**
 * 
 */
package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

/**
 * pack the container/archive, then hand it off to SimpleFileInserter
 *
 * TODO fix resolve metadata
 *     -how to format the redirects/submetadata?
 *     -the current metadataMap thingy s***s, invent a working one ;)
 * TODO persistence
 * TODO add a MAX_SIZE for the final container(file)
 * 
 * @author saces
 * 
 */
public class ContainerInserter implements ClientPutState {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
			}
		});
	}


	private static class ContainerElement {
		private final Bucket data;
		private final String targetInArchive;
		
		private ContainerElement(Bucket data2, String targetInArchive2) {
			data = data2;
			targetInArchive = targetInArchive2;
		}
	}

	private ArrayList<ContainerElement> containerItems;
	private HashMap<Metadata, Entry<String, Object>> metadataMap;

	private final BaseClientPutter parent;
	private final PutCompletionCallback cb;
	private boolean cancelled;
	private boolean finished;
	private final boolean persistent;
	private final HashMap<String, Object> origMetadata;
	private final ARCHIVE_TYPE archiveType;
	private final FreenetURI targetURI;
	private final Object token;
	private final boolean getCHKOnly;
	private final boolean earlyEncode;
	private final InsertContext ctx;
	private final boolean reportMetadataOnly;
	private final boolean dontCompress;

	/**
	 * @param metadata2 
	 * @param archiveType2 
	 * @param targetURI2 
	 * @param token2 
	 * @param getCHKOnly2 
	 * @param earlyEncode2 
	 * @param ctx2 
	 * @param reportMetadataOnly2 
	 * 
	 */
	public ContainerInserter(
			BaseClientPutter parent2, 
			PutCompletionCallback cb2, 
			HashMap<String, Object> metadata2,
			FreenetURI targetURI2,
			InsertContext ctx2,
			boolean dontCompress2,
			boolean getCHKOnly2,
			boolean reportMetadataOnly2,
			Object token2,
			ARCHIVE_TYPE archiveType2,
			boolean freeData,
			boolean earlyEncode2) {
		parent = parent2;
		cb = cb2;
		hashCode = super.hashCode();
		persistent = parent.persistent();
		origMetadata = metadata2;
		archiveType = archiveType2;
		targetURI = targetURI2;
		token = token2;
		getCHKOnly = getCHKOnly2;
		earlyEncode = earlyEncode2;
		ctx = ctx2;
		dontCompress = dontCompress2;
		reportMetadataOnly = reportMetadataOnly2;
		containerItems = new ArrayList<ContainerElement>();
		metadataMap = new HashMap<Metadata, Entry<String, Object>>();
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(cancelled) return;
			cancelled = true;
		}
		if(persistent)
			container.store(this);
		if(persistent)
			container.activate(cb, 1);
		// Must call onFailure so get removeFrom()'ed
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public Object getToken() {
		return token;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		// TODO
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(container, context);
	}


	private void start(ObjectContainer container, ClientContext context) {
		if(logDEBUG) Logger.debug(this, "Atempt to start a container inserter", new Exception("debug"));
		
		makeMetadata(context, container);
		
		synchronized(this) {
			if(finished) return;
		}
		
		InsertBlock block;
		try {
			Bucket outputBucket = context.getBucketFactory(persistent).makeBucket(-1);
			String mimeType = (archiveType == ARCHIVE_TYPE.TAR ?
				createTarBucket(outputBucket, container) :
				createZipBucket(outputBucket, container));
			
			if(logMINOR) Logger.minor(this, "We are using "+archiveType);
			
			// Now we have to insert the Archive we have generated.
			
			// Can we just insert it, and not bother with a redirect to it?
			// Thereby exploiting implicit manifest support, which will pick up on .metadata??
			// We ought to be able to !!
			block = new InsertBlock(outputBucket, new ClientMetadata(mimeType), persistent ? targetURI.clone() : targetURI);
		} catch (IOException e) {
			fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
			return;
		}
		
		boolean dc = dontCompress;
		if (!dontCompress) {
			dc = (archiveType == ARCHIVE_TYPE.ZIP);
		}
		
		SingleFileInserter sfi = new SingleFileInserter(parent, cb, block, false, ctx, dc, getCHKOnly, reportMetadataOnly, token, archiveType, true, null, earlyEncode);
		if(logMINOR)
			Logger.minor(this, "Inserting container: "+sfi+" for "+this);
		cb.onTransition(this, sfi, container);
		try {
			sfi.schedule(container, context);
		} catch (InsertException e) {
			fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
			return;
		}
		cb.onBlockSetFinished(this, container, context);
	}

	private void makeMetadata(ClientContext context, ObjectContainer container) {
		
		Bucket bucket = null;
		int x = 0;
		
		while(true) {
			try {
				HashMap<String,Object> manifest = new HashMap<String,Object>();
				makeManifest(origMetadata, manifest, "/");
				Metadata md = Metadata.mkRedirectionManifestWithMetadata(manifest);
				bucket = context.tempBucketFactory.makeBucket(-1);
				byte[] buf = md.writeToByteArray();
				OutputStream os = bucket.getOutputStream();
				os.write(buf);
				os.close();
				containerItems.add(new ContainerElement(bucket, ".metadata"));
				return;
			} catch (IOException e) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);
				return;
			} catch (MetadataUnresolvedException e) {
				try {
					x = resolve(e, x, bucket, null, null, container, context);
				} catch (IOException e1) {
					fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);
					return;
				}
			}
		}
		
	}

	private int resolve(MetadataUnresolvedException e, int x, Bucket bucket, FreenetURI key, String element2, ObjectContainer container, ClientContext context) throws IOException {
		Metadata[] m = e.mustResolve;
		for(int i=0;i<m.length;i++) {
			try {
				byte[] buf = m[i].writeToByteArray();
				OutputStream os = bucket.getOutputStream();
				os.write(buf);
				os.close();
				String nameInArchive = ".metadata-"+(x++);
				containerItems.add(new ContainerElement(bucket, nameInArchive));
				Entry<String, Object> entry = metadataMap.get(m[i]);
				entry.setValue(new Metadata(Metadata.ARCHIVE_INTERNAL_REDIRECT, null, null, nameInArchive, null));
			} catch (MetadataUnresolvedException e1) {
				x = resolve(e, x, bucket, key, element2, container, context);
			}
		}
		return x;
	}

	private void fail(InsertException e, ObjectContainer container, ClientContext context) {
		// Cancel all, then call the callback
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onFailure(e, this, container, context);
	}

	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	public boolean objectCanUpdate(@SuppressWarnings("unused") ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanUpdate() on "+this, new Exception("debug"));
		return true;
	}
	
	public boolean objectCanNew(@SuppressWarnings("unused") ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "objectCanNew() on "+this, new Exception("debug"));
		return true;
	}
	

	
	private String createTarBucket(Bucket outputBucket, @SuppressWarnings("unused") ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a TAR Bucket");
		
		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		TarOutputStream tarOS = new TarOutputStream(os);
		tarOS.setLongFileMode(TarOutputStream.LONGFILE_GNU);
		TarEntry ze;

		for(ContainerElement ph : containerItems) {
			if(logMINOR)
				Logger.minor(this, "Putting into tar: "+ph+" data length "+ph.data.size()+" name "+ph.targetInArchive);
			ze = new TarEntry(ph.targetInArchive);
			ze.setModTime(0);
			long size = ph.data.size();
			ze.setSize(size);
			tarOS.putNextEntry(ze);
			BucketTools.copyTo(ph.data, tarOS, size);
			tarOS.closeEntry();
		}

		tarOS.closeEntry();
		// Both finish() and close() are necessary.
		tarOS.finish();
		tarOS.flush();
		tarOS.close();
		
		if(logMINOR)
			Logger.minor(this, "Archive size is "+outputBucket.size());
		
		return ARCHIVE_TYPE.TAR.mimeTypes[0];
	}
	
	private String createZipBucket(Bucket outputBucket, @SuppressWarnings("unused") ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a ZIP Bucket");
		
		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		ZipOutputStream zos = new ZipOutputStream(os);
		ZipEntry ze;

		for(ContainerElement ph: containerItems) {
			ze = new ZipEntry(ph.targetInArchive);
			ze.setTime(0);
			zos.putNextEntry(ze);
			BucketTools.copyTo(ph.data, zos, ph.data.size());
			zos.closeEntry();
		}

		zos.closeEntry();
		// Both finish() and close() are necessary.
		zos.finish();
		zos.flush();
		zos.close();
		
		return ARCHIVE_TYPE.ZIP.mimeTypes[0];
	}

	private void makeManifest(HashMap<String, Object> manifestElements, HashMap<String,Object> manifest, String archivePrefix) {
		for (Entry<String, Object>entry:manifestElements.entrySet()) {
			String name = entry.getKey();
			Object o = entry.getValue();
			if(o instanceof HashMap) {
				@SuppressWarnings("unchecked")
				HashMap<String,Object> hm = (HashMap<String, Object>) o;
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				//System.out.println("Decompose: "+name+" (SubDir)");
				manifest.put(name, subMap);
				makeManifest(hm, subMap, archivePrefix+name+ '/');
				if(Logger.shouldLog(Logger.DEBUG, this))
					Logger.debug(this, "Sub map for "+name+" : "+subMap.size()+" elements from "+hm.size());
			} else if (o instanceof Metadata) {
				//already Metadata, take as is
				//System.out.println("Decompose: "+name+" (Metadata)");
				manifest.put(name, o);
				metadataMap.put((Metadata) o, entry);
			} else {
				ManifestElement element = (ManifestElement) o;
				String mimeType = element.mimeOverride;
				ClientMetadata cm;
				if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
					cm = null;
				else
					cm = new ClientMetadata(mimeType);
				Metadata m;
				if(element.targetURI != null) {
					//System.out.println("Decompose: "+name+" (ManifestElement, Redirect)");
					m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, element.targetURI, cm);
				} else {
					//System.out.println("Decompose: "+name+" (ManifestElement, Data)");
					containerItems.add(new ContainerElement(element.data, archivePrefix+name));
					m = new Metadata(Metadata.ARCHIVE_INTERNAL_REDIRECT, null, null, archivePrefix+element.fullName, cm);
				}
				manifest.put(name, m);
				metadataMap.put(m, entry);
			}
		}
	}
}