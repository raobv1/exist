/*
 *  eXist Open Source Native XML Database
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
 *  $Id$
 */
package org.exist.storage.cache;

import org.exist.storage.CacheManager;
import org.exist.util.hashtable.Long2ObjectHashMap;

/**
 * Cache implementation based on the GClock algorithm. 
 * 
 * Implements a mixture between LFU (Last Frequently Used) and LRU 
 * (Last Recently Used) replacement policies. The class 
 * uses reference counts to track references to cached objects. Each call to the add 
 * method increments the reference count of the object.
 * 
 * If the cache is full, the object to be removed is determined by decrementing the
 * reference count for each object until an object with reference count = 0 is found. 
 * 
 * The implementation tends to replace younger objects first.
 * 
 * @author wolf
 */
public class GClockCache implements Cache {

	protected Cacheable[] items;
	protected int count = 0;
	protected int size;
	protected Long2ObjectHashMap map;
	protected int used = 0;
	protected int hits = 0 ;
    protected int hitsOld = -1;
	protected int fails = 0 ;
    protected double growthFactor;
    protected int growthThreshold;
	protected CacheManager cacheManager = null;
    protected int replacements = 0;
    private String fileName = "unknown";
    
	public GClockCache(int size, double growthFactor, int growthThreshold) {
		this.size = size;
        this.growthFactor = growthFactor;
        this.growthThreshold = growthThreshold;
		this.items = new Cacheable[size];
		this.map = new Long2ObjectHashMap(size);
	}

	public void add(Cacheable item) {
		add(item, 1);
	}

	public void add(Cacheable item, int initialRefCount) {
		Cacheable old = (Cacheable) map.get(item.getKey());
		if (old != null) {
			old.incReferenceCount();
			return;
		}
		item.setReferenceCount(initialRefCount);
		if (count < size) {
			items[count++] = item;
			map.put(item.getKey(), item);
			used++;
		} else {
			removeOne(item);
		}
	}

	public Cacheable get(Cacheable item) {
		return get(item.getKey());
	}

	public Cacheable get(long key) {
		Cacheable item = (Cacheable) map.get(key);
		if (item == null) {
			fails++;
		} else
			hits++;
		return item;
	}

	public void remove(Cacheable item) {
		long key = item.getKey();
		Cacheable cacheable = (Cacheable) map.remove(key);
		if (cacheable == null)
			return;
		for (int i = 0; i < count; i++) {
			if (items[i] != null && items[i].getKey() == key) {
				items[i] = null;
				used--;
				return;
			}
		}
		LOG.error("item not found in list");
	}

	public void flush() {
	    int written = 0;
		for (int i = 0; i < count; i++) {
			if (items[i] != null && items[i].sync())
			    ++written;
		}
//		LOG.debug(written + " pages written to disk");
	}

	public boolean hasDirtyItems() {
	    for(int i = 0; i < count; i++) {
			if(items[i] != null && items[i].isDirty())
				return true;
		}
	    return false;
	}
	
	protected Cacheable removeOne(Cacheable item) {
		Cacheable old = null;
		boolean removed = false;
		int bucket;
		do {
			bucket = -1;
			// decrease all reference counts by 1
			for (int i = 0; i < count; i++) {
				old = items[i];
				if (old == null) {
					bucket = i;
				} else if (old.decReferenceCount() < 1 && bucket < 0) {
					bucket = i;
				}
			}

			if (bucket > -1) {
				old = items[bucket];
				if (old != null) {
					//LOG.debug(fileName + " replacing " + old.getKey() + " for " + item.getKey());
					map.remove(old.getKey());
					old.sync();
				} else {
					used++;
				}
				items[bucket] = item;
				map.put(item.getKey(), item);
				removed = true;
			}
		} while (!removed);
        
        if (cacheManager != null && ++replacements > growthThreshold) {
            cacheManager.requestMem(this);
            replacements = 0;
        }
		return old;
	}

	public int getBuffers() {
		return size;
	}

	public int getUsedBuffers() {
		return used;
	}

    public double getGrowthFactor() {
        return growthFactor;
    }
    
	public int getHits() {
		return hits;
	}

	public int getFails() {
		return fails;
	}

    public void setCacheManager(CacheManager manager) {
        this.cacheManager = manager;
    }
    
    public void resize(int newSize) {
        if (newSize < size) {
            shrink(newSize);
        } else {
            LOG.debug("Growing cache " + fileName + " from " + size + " to " + newSize);
            Cacheable[] newItems = new Cacheable[newSize];
            Long2ObjectHashMap newMap = new Long2ObjectHashMap(newSize);
            for (int i = 0; i < count; i++) {
                newItems[i] = items[i];
                newMap.put(items[i].getKey(), items[i]);
            }
            this.size = newSize;
            this.map = newMap;
            this.items = newItems;
        }
        this.replacements = 0;
    }
    
    protected void shrink(int newSize) {
        flush();
        items = new Cacheable[newSize];
        map = new Long2ObjectHashMap(newSize);
        size = newSize;
        count = 0;
        used = 0;
    }

    public int getLoad() {
        if (hitsOld == -1)
            return -1;
        int load = hits - hitsOld;
        hitsOld = hits;
        return load;
    }
  
    public void setFileName(String name) {
        fileName = name;
    }
}
