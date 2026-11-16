package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.Iterator;
import org.junit.Test;

public class SparseIterableArrayTest {
  @Test
  public void remove_removesLastReturnedElement() {
    SparseIterableArray<String> array = new SparseIterableArray<>();
    array.put(0, "a");
    array.put(1, "b");
    array.put(2, "c");
    Iterator<String> iterator = array.iterator();
    assertEquals("a", iterator.next());
    assertEquals("b", iterator.next());
    iterator.remove();
    assertEquals(2, array.size());
    assertEquals("a", array.valueAt(0));
    assertEquals("c", array.valueAt(1));
    assertEquals("c", iterator.next());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void remove_beforeNextThrows() {
    SparseIterableArray<String> array = new SparseIterableArray<>();
    array.put(0, "a");
    try {
      array.iterator().remove();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      // expected
    }
  }

  @Test
  public void remove_twiceWithoutNextThrows() {
    SparseIterableArray<String> array = new SparseIterableArray<>();
    array.put(0, "a");
    array.put(1, "b");
    Iterator<String> iterator = array.iterator();
    iterator.next();
    iterator.remove();
    try {
      iterator.remove();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      // expected
    }
  }

  @Test
  public void remove_lastElementSucceeds() {
    SparseIterableArray<String> array = new SparseIterableArray<>();
    array.put(0, "a");
    array.put(1, "b");
    Iterator<String> iterator = array.iterator();
    iterator.next();
    iterator.next();
    iterator.remove();
    assertEquals(1, array.size());
    assertEquals("a", array.valueAt(0));
    assertFalse(iterator.hasNext());
  }
}
