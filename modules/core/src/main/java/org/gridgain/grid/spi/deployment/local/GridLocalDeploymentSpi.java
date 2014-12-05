/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.deployment.local;

import org.apache.ignite.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.deployment.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.Map.*;
import java.util.concurrent.*;

/**
 * Local deployment SPI that implements only within VM deployment on local
 * node via {@link #register(ClassLoader, Class)} method. This SPI requires
 * no configuration.
 * <p>
 * Note that if peer class loading is enabled (which is default behavior,
 * see {@link org.apache.ignite.configuration.IgniteConfiguration#isPeerClassLoadingEnabled()}), then it is
 * enough to deploy a task only on one node and all other nodes will load
 * required classes from the node that initiated task execution.
 * <p>
 * <h1 class="header">Configuration</h1>
 * This SPI requires no configuration.
 * <h2 class="header">Example</h2>
 * There is no point to explicitly configure {@code GridLocalDeploymentSpi}
 * with {@link org.apache.ignite.configuration.IgniteConfiguration} as it is used by default and has no
 * configuration parameters.
 * @see GridDeploymentSpi
 */
@IgniteSpiMultipleInstancesSupport(true)
@IgniteSpiConsistencyChecked(optional = false)
@GridIgnoreIfPeerClassLoadingDisabled
public class GridLocalDeploymentSpi extends IgniteSpiAdapter implements GridDeploymentSpi, GridLocalDeploymentSpiMBean {
    /** */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    @IgniteLoggerResource
    private IgniteLogger log;

    /** Map of all resources. */
    private ConcurrentLinkedHashMap<ClassLoader, ConcurrentMap<String, String>> ldrRsrcs =
        new ConcurrentLinkedHashMap<>(16, 0.75f, 64);

    /** Deployment SPI listener.    */
    private volatile GridDeploymentListener lsnr;

    /** {@inheritDoc} */
    @Override public void spiStart(@Nullable String gridName) throws IgniteSpiException {
        // Start SPI start stopwatch.
        startStopwatch();

        registerMBean(gridName, this, GridLocalDeploymentSpiMBean.class);

        if (log.isDebugEnabled())
            log.debug(startInfo());
    }

    /** {@inheritDoc} */
    @Override public void spiStop() throws IgniteSpiException {
        unregisterMBean();

        for (ClassLoader ldr : ldrRsrcs.descendingKeySet())
            onClassLoaderReleased(ldr);

        if (log.isDebugEnabled())
            log.debug(stopInfo());
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridDeploymentResource findResource(String rsrcName) {
        assert rsrcName != null;

        // Last updated class loader has highest priority in search.
        for (Entry<ClassLoader, ConcurrentMap<String, String>> e : ldrRsrcs.descendingEntrySet()) {
            ClassLoader ldr = e.getKey();
            ConcurrentMap<String, String> rsrcs = e.getValue();

            String clsName = rsrcs.get(rsrcName);

            // Return class if it was found in resources map.
            if (clsName != null) {
                // Recalculate resource name in case if access is performed by
                // class name and not the resource name.
                rsrcName = getResourceName(clsName, rsrcs);

                assert clsName != null;

                try {
                    Class<?> cls = Class.forName(clsName, true, ldr);

                    assert cls != null;

                    // Return resource.
                    return new GridDeploymentResourceAdapter(rsrcName, cls, ldr);
                }
                catch (ClassNotFoundException ignored) {
                    // No-op.
                }
            }
        }

        return null;
    }

    /**
     * Gets resource name for a given class name.
     *
     * @param clsName Class name.
     * @param rsrcs Map of resources.
     * @return Resource name.
     */
    private String getResourceName(String clsName, Map<String, String> rsrcs) {
        String rsrcName = clsName;

        for (Entry<String, String> e : rsrcs.entrySet()) {
            if (e.getValue().equals(clsName) && !e.getKey().equals(clsName)) {
                rsrcName = e.getKey();

                break;
            }
        }

        return rsrcName;
    }

    /** {@inheritDoc} */
    @Override public boolean register(ClassLoader ldr, Class<?> rsrc) throws IgniteSpiException {
        assert ldr != null;
        assert rsrc != null;

        if (log.isDebugEnabled())
            log.debug("Registering [ldrRsrcs=" + ldrRsrcs + ", ldr=" + ldr + ", rsrc=" + rsrc + ']');

        ConcurrentMap<String, String> clsLdrRsrcs = ldrRsrcs.getSafe(ldr);

        if (clsLdrRsrcs == null) {
            ConcurrentMap<String, String> old = ldrRsrcs.putIfAbsent(ldr,
                clsLdrRsrcs = new ConcurrentHashMap8<>());

            if (old != null)
                clsLdrRsrcs = old;
        }

        Map<String, String> newRsrcs = addResource(ldr, clsLdrRsrcs, rsrc);

        Collection<ClassLoader> rmvClsLdrs = null;

        if (!F.isEmpty(newRsrcs)) {
            rmvClsLdrs = new LinkedList<>();

            removeResources(ldr, newRsrcs, rmvClsLdrs);
        }

        if (rmvClsLdrs != null) {
            for (ClassLoader cldLdr : rmvClsLdrs)
                onClassLoaderReleased(cldLdr);
        }

        return !F.isEmpty(newRsrcs);
    }

    /** {@inheritDoc} */
    @Override public boolean unregister(String rsrcName) {
        Collection<ClassLoader> rmvClsLdrs = new LinkedList<>();

        Map<String, String> rsrcs = U.newHashMap(1);

        rsrcs.put(rsrcName, rsrcName);

        boolean rmv = removeResources(null, rsrcs, rmvClsLdrs);

        for (ClassLoader cldLdr : rmvClsLdrs)
            onClassLoaderReleased(cldLdr);

        return rmv;
    }

    /**
     * Add new classes in class loader resource map.
     * Note that resource map may contain two entries for one added class:
     * task name -> class name and class name -> class name.
     *
     * @param ldr Registered class loader.
     * @param ldrRsrcs Class loader resources.
     * @param cls Registered classes collection.
     * @return Map of new resources added for registered class loader.
     * @throws org.gridgain.grid.spi.IgniteSpiException If resource already registered. Exception thrown
     * if registered resources conflicts with rule when all task classes must be
     * annotated with different task names.
     */
    @Nullable private Map<String, String> addResource(ClassLoader ldr, ConcurrentMap<String, String> ldrRsrcs,
        Class<?> cls) throws IgniteSpiException {
        assert ldr != null;
        assert ldrRsrcs != null;
        assert cls != null;

        // Maps resources to classes.
        // Map may contain 2 entries for one class.
        Map<String, String> regRsrcs = new HashMap<>(2, 1.0f);

        // Check alias collision for added classes.
        String alias = null;

        if (ComputeTask.class.isAssignableFrom(cls)) {
            ComputeTaskName nameAnn = GridAnnotationsCache.getAnnotation(cls, ComputeTaskName.class);

            if (nameAnn != null)
                alias = nameAnn.value();
        }

        if (alias != null)
            regRsrcs.put(alias, cls.getName());

        regRsrcs.put(cls.getName(), cls.getName());

        if (log.isDebugEnabled())
            log.debug("Resources to register: " + regRsrcs);

        Map<String, String> newRsrcs = null;

        // Check collisions for added classes.
        for (Entry<String, String> entry : regRsrcs.entrySet()) {
            String oldCls = ldrRsrcs.putIfAbsent(entry.getKey(), entry.getValue());

            if (oldCls != null) {
                if (!oldCls.equals(entry.getValue()))
                    throw new IgniteSpiException("Failed to register resources with given task name " +
                        "(found another class with same task name in the same class loader) " +
                        "[taskName=" + entry.getKey() + ", existingCls=" + oldCls +
                        ", newCls=" + entry.getValue() + ", ldr=" + ldr + ']');
            }
            else {
                // New resource was added.
                if (newRsrcs == null)
                    newRsrcs = U.newHashMap(regRsrcs.size());

                newRsrcs.put(entry.getKey(), entry.getValue());
            }
        }

        // New resources to register. Add it all.
        if (newRsrcs != null)
            ldrRsrcs.putAll(newRsrcs);

        if (log.isDebugEnabled())
            log.debug("New resources: " + newRsrcs);

        return newRsrcs;
    }

    /**
     * Remove resources for all class loaders except {@code ignoreClsLdr}.
     *
     * @param clsLdrToIgnore Ignored class loader or {@code null} to remove for all class loaders.
     * @param rsrcs Resources that should be used in search for class loader to remove.
     * @param rmvClsLdrs Class loaders to remove.
     * @return {@code True} if resource was removed.
     */
    private boolean removeResources(@Nullable ClassLoader clsLdrToIgnore, Map<String, String> rsrcs,
        Collection<ClassLoader> rmvClsLdrs) {
        assert rsrcs != null;

        if (log.isDebugEnabled())
            log.debug("Removing resources [clsLdrToIgnore=" + clsLdrToIgnore + ", rsrcs=" + rsrcs + ']');

        boolean res = false;

        for (Entry<ClassLoader, ConcurrentMap<String, String>> e : ldrRsrcs.descendingEntrySet()) {
            ClassLoader ldr = e.getKey();

            if (clsLdrToIgnore == null || !ldr.equals(clsLdrToIgnore)) {
                Map<String, String> clsLdrRsrcs = e.getValue();

                boolean isRmv = false;

                // Check class loader registered resources.
                for (String rsrcName : rsrcs.keySet()) {
                    // Remove class loader if resource found.
                    if (clsLdrRsrcs.containsKey(rsrcName) && ldrRsrcs.remove(ldr, clsLdrRsrcs)) {
                        // Add class loaders in collection to notify listener outside synchronization block.
                        rmvClsLdrs.add(ldr);

                        isRmv = true;
                        res = true;

                        if (log.isDebugEnabled())
                            log.debug("Removed resources [ldr=" + ldr + ", rsrcs=" + clsLdrRsrcs + ']');

                        break;
                    }
                }

                if (isRmv)
                    continue;

                // Check is possible to load resources with class loader.
                for (Entry<String, String> entry : rsrcs.entrySet()) {
                    // Check classes with class loader only when classes points to classes to avoid redundant check.
                    // Resources map contains two entries for class with task name(alias).
                    if (entry.getKey().equals(entry.getValue()) && isResourceExist(ldr, entry.getKey()) &&
                        !U.hasParent(clsLdrToIgnore, ldr) && ldrRsrcs.remove(ldr, clsLdrRsrcs)) {
                        // Add class loaders in collection to notify listener outside synchronization block.
                        rmvClsLdrs.add(ldr);

                        if (log.isDebugEnabled())
                            log.debug("Removed resources after checking existence [ldr=" + ldr +
                                ", clsLdrRsrcs=" + clsLdrRsrcs + ", rsrcs=" + rsrcs + ']');

                        res = true;

                        break;
                    }
                }
            }
        }

        return res;
    }

    /**
     * Check is class can be reached.
     *
     * @param ldr Class loader.
     * @param clsName Class name.
     * @return {@code true} if class can be loaded.
     */
    private boolean isResourceExist(ClassLoader ldr, String clsName) {
        assert ldr != null;
        assert clsName != null;

        String rsrcName = clsName.replaceAll("\\.", "/") + ".class";

        InputStream in = null;

        try {
            in = ldr.getResourceAsStream(rsrcName);

            return in != null;
        }
        finally {
            U.closeQuiet(in);
        }
    }

    /**
     * Notifies listener about released class loader.
     *
     * @param clsLdr Released class loader.
     */
    private void onClassLoaderReleased(ClassLoader clsLdr) {
        GridDeploymentListener tmp = lsnr;

        if (tmp != null)
            tmp.onUnregistered(clsLdr);
    }

    /** {@inheritDoc} */
    @Override public void setListener(GridDeploymentListener lsnr) {
        this.lsnr = lsnr;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridLocalDeploymentSpi.class, this);
    }
}
