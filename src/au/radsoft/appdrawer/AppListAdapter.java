package au.radsoft.appdrawer;

import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.content.Context;
import android.content.Intent;
//import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;

public class AppListAdapter extends BaseAdapter
{
    private static final class Info implements java.io.Serializable
    {
        private static final long serialVersionUID = 2L;
        
        private /*final*/ boolean enabled_;
        private /*final*/ CharSequence label_;
        private /*final*/ long firstInstallTime_;
        private /*final*/ long lastUpdateTime_;
        
        Info(boolean enabled, CharSequence label, long firstInstallTime, long lastUpdateTime)
        {
            enabled_ = enabled;
            label_ = label;
            firstInstallTime_ = firstInstallTime;
            lastUpdateTime_ = lastUpdateTime;
        }
        
        private void writeObject(java.io.ObjectOutputStream out)
            throws java.io.IOException
        {
            out.writeBoolean(enabled_);
            out.writeUTF(label_.toString());
            out.writeLong(firstInstallTime_);
            out.writeLong(lastUpdateTime_);
        }
        
        private void readObject(java.io.ObjectInputStream in)
            throws java.io.IOException, ClassNotFoundException
        {
            enabled_ = in.readBoolean();
            label_ = in.readUTF();
            firstInstallTime_ = in.readLong();
            lastUpdateTime_ = in.readLong();
        }
    }
    
    private static final class App
    {
        App(ResolveInfo ri, ApplicationInfo ai, Info info)
        {
            ri_ = ri;
            ai_ = ai;
            info_ = info;
        }
        
        void loadDrawable(PackageManager pm)
        {
            if (img_ == null)
            {
                if (ri_ != null)
                    img_ = ri_.loadIcon(pm);
                else
                    img_ = pm.getApplicationIcon(ai_);
            }
        }
        
        boolean isEnabled()
        {
            return ai_.enabled;
        }
        
        String getPackageName()
        {
            return ai_.packageName;
        }
        
        boolean canLaunch()
        {
            return ri_ != null;
        }
        
        private final ResolveInfo ri_;
        private final ApplicationInfo ai_;
        private final Info info_;
        private Drawable img_ = null;
    }
    
    private static final class LabelComparator implements Comparator<App>
    {
        @Override
        public int compare(App p1, App p2)
        {
            return au.radsoft.utils.CharSequenceUtils.compareIgnoreCase(p1.info_.label_, p2.info_.label_);
        }

        @Override
        public boolean equals(Object p1)
        {
            return this == p1;
        }
    }
    
    private final class FilterAsyncTask extends AsyncTask<String, Integer, List<App>>
    {
        private final View progress_;
        private final ProgressBar progressBar_;

        FilterAsyncTask(View progress)
        {
            progress_ = progress;
            if (progress_ != null)
                progressBar_ = (ProgressBar) progress_.findViewById(R.id.progress_bar);
            else
                progressBar_ = null;

            if (filterAsyncTask_ != null)
                filterAsyncTask_.cancel(false);
            filterAsyncTask_ = this;
        }

        @Override
        protected List<App> doInBackground(String... texts)
        {
            if (all_ == null)
                all_ = loadApps();
            return filterApps(pm_, all_, texts, this);
        }
        
        @Override
        protected void onProgressUpdate(Integer... progress)
        {
            if (progressBar_ != null)
            {
                if (progress.length > 1)
                {
                    progress_.setVisibility(View.VISIBLE);
                    progressBar_.setMax(progress[1]);
                    progressBar_.setProgress(progress[0]);
                }
                else if (progress.length > 0)
                    progressBar_.incrementProgressBy(progress[0]);
            }
        }
        
        @Override
        protected void onPostExecute(List<App> result)
        {
            super.onPostExecute(result);
            if (filterAsyncTask_ == this)
                filterAsyncTask_ = null;
            apps_ = result;
            if (progress_ != null)
                progress_.setVisibility(View.GONE);
            notifyDataSetChanged();
        }

        @Override
        protected void onCancelled()
        {
            super.onCancelled();
            if (progress_ != null)
                progress_.setVisibility(View.GONE);
        }
    
        private List<App> loadApps()
        {
            java.io.File infoFile = new java.io.File(cacheDir_, "info");
            
            java.util.Map<String, Info> infoCache = (java.util.Map<String, Info>) loadObject(infoFile);
            if (infoCache == null)
                infoCache = new java.util.HashMap<String, Info>();
            java.util.Map<String, Info> infoCacheNew = new java.util.HashMap<String, Info>();

            boolean showProgress = infoCache.isEmpty();
            boolean dirty = false;
            
            List<App> apps = new java.util.ArrayList<App>();

            List<PackageInfo> installed = pm_.getInstalledPackages(0);
            if (showProgress)
                publishProgress(0, installed.size());
            for (PackageInfo pi : installed)
            {
                Info info = infoCache.get(pi.packageName);

                ResolveInfo ri = null;
                if (pi.applicationInfo.enabled)
                {
                    Intent intent = pm_.getLaunchIntentForPackage(pi.packageName);
                    if (intent != null)
                        // NOTE resolveActivity returns null for disabled packages.
                        ri = pm_.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                }
                
                if (info == null
                    || pi.applicationInfo.enabled != info.enabled_
                    || pi.lastUpdateTime > info.lastUpdateTime_)
                {
                    if (ri != null)
                        info = new Info(pi.applicationInfo.enabled, ri.loadLabel(pm_), pi.firstInstallTime, pi.lastUpdateTime);
                    else
                        // TODO The label could change depending on whether the app is disabled
                        //      So either do not cache label for disabled apps
                        //      or cache disabled status as well so we can see when it has changed
                        info = new Info(pi.applicationInfo.enabled, pm_.getApplicationLabel(pi.applicationInfo), pi.firstInstallTime, pi.lastUpdateTime);
                    dirty = true;
                }
                
                App app = new App(ri, pi.applicationInfo, info);
                apps.add(app);
                infoCacheNew.put(pi.packageName, info);
                
                if (showProgress)
                    publishProgress(1);
            }
            
            if (dirty || infoCacheNew.size() != infoCache.size())
                storeObject(infoFile, infoCacheNew);
            
            return apps;
        }
    }
    
    static void Set(ImageView v, DrawableLoaderAsyncTask t)
    {
        AsyncTask oldTask = (AsyncTask) v.getTag();
        v.setTag(t);
        if (oldTask != null)
            oldTask.cancel(false);
    }
    
    private final class DrawableLoaderAsyncTask extends AsyncTask<App, Void, Drawable>
    {
        private final java.lang.ref.WeakReference<ImageView> v_;
        
        DrawableLoaderAsyncTask(ImageView v)
        {
            v_ = new java.lang.ref.WeakReference<ImageView>(v);
        
            Set(v, this);
        }
        
        @Override
        protected Drawable doInBackground(App ... apps)
        {
            apps[0].loadDrawable(pm_);
            return apps[0].img_;
        }
        
        @Override
        protected void onPostExecute(Drawable result)
        {
            ImageView v = v_.get();
            if (v != null && v.getTag() == this)
            {
                v.setImageDrawable(result);
                v.setTag(null);
            }
        }
    }
    
    private static List<App> filterApps(PackageManager pm, List<App> all, String[] text, AsyncTask task)
    {
        List<App> apps = new java.util.ArrayList<App>();
        
        final long time = System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 7;
        boolean testEnabled = true;
        boolean testDisabled = false;
        boolean testLaunch = true;
        boolean testInstallTime = false;
        boolean testUpdateTime = false;
        
        for (String t : text)
        {
            if (!t.isEmpty() && t.charAt(0) == '#')
            {
                if ("#all".equalsIgnoreCase(t))
                {
                    testEnabled = false;
                    testDisabled = false;
                    testLaunch = false;
                }
                else if ("#disabled".equalsIgnoreCase(t))
                {
                    testEnabled = false;
                    testDisabled = true;
                    testLaunch = false;
                }
                else if ("#new".equalsIgnoreCase(t))
                {
                    testInstallTime = true;
                }
                else if ("#updated".equalsIgnoreCase(t))
                {
                    testUpdateTime = true;
                }
            }
        }
        
        for (App app : all)
        {
            if (   (!testEnabled     || app.isEnabled())
                && (!testDisabled    || !app.isEnabled())
                && (!testLaunch      || app.canLaunch())
                && (!testInstallTime || app.info_.firstInstallTime_ > time)
                && (!testUpdateTime  || app.info_.lastUpdateTime_ > time))
            {
                if (findAll(app.getPackageName() + " " + app.info_.label_, text))
                {
                    apps.add(app);
                }
            }
            
            if (task.isCancelled())
                break;
        }
        
        // NOTE LabelComparator assumes that labels have been loaded
        if (!task.isCancelled())
            Collections.sort(apps, new LabelComparator());
        
        return apps;
    }
    
    // TODO Should we use this? The whole app is a suggestions list!
    void getSuggestions(String query, List<CharSequence> suggestions)
    {
        for (App app : apps_)
        {
            int b = au.radsoft.utils.CharSequenceUtils.findIgnoreCase(app.info_.label_, 0, query);
            if (b == 0 || (b > 0 && app.info_.label_.charAt(b - 1) == ' '))
            {
                int e = au.radsoft.utils.CharSequenceUtils.find(app.info_.label_, b, ' ');
                if (e < 0)
                    suggestions.add(app.info_.label_.subSequence(b, app.info_.label_.length()));
                else
                    suggestions.add(app.info_.label_.subSequence(b, e));
            }
        }
    }
    
    static boolean findAll(CharSequence str, String[] text)
    {
        for (String t : text)
        {
            if (!t.isEmpty() && t.charAt(0) != '#' && au.radsoft.utils.CharSequenceUtils.findIgnoreCase(str, 0, t) < 0)
                return false;
        }
        return true;
    }
    
    static void storeObject(java.io.File f, Object o)
    {
        try
        {
            java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(new java.io.FileOutputStream(f));
            out.writeObject(o);
            out.close();
        }
        catch (java.io.IOException e)
        {
            e.printStackTrace();
            //toast(context, "Saving cache: " + e);
        }
    }
    
    static Object loadObject(java.io.File f)
    {
        try
        {
            Object o = null;
            if (f.exists())
            {
                java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.FileInputStream(f));
                o = in.readObject();
                in.close();
            }
            return o;
        }
        catch (ClassNotFoundException e)
        {
            e.printStackTrace();
            //toast(context, "Loading cache: " + e);
            return null;
        }
        catch (java.io.IOException e)
        {
            e.printStackTrace();
            //toast(context, "Loading cache: " + e);
            return null;
        }
    }
    
    private static List<App> all_= null;
    
    private final LayoutInflater layoutInflater_;
    private final PackageManager pm_;
    private final java.io.File cacheDir_;
    
    private List<App> apps_ = new java.util.ArrayList<App>();
    private FilterAsyncTask filterAsyncTask_ = null;

    public AppListAdapter(PackageManager pm, LayoutInflater layoutInflater, View progress, java.io.File cacheDir)
    {
        layoutInflater_ = layoutInflater;
        pm_ = pm;
        cacheDir_ = cacheDir;
        
        String[] text = { };
        new FilterAsyncTask(progress).execute(text);
        //all_ = loadApps(pm_);
        //apps_ = filterApps(pm, all_, null);
    }
    
    public void filter(String text)
    {
        if (all_ != null)
        {
            //text = text.trim();
            //text = text.replaceAll("\\s+", " ");
            new FilterAsyncTask(null).execute(text.split(" "));
        }
        else
        {
            apps_.clear();
            notifyDataSetChanged();
        }
    }

    @Override
    public int getCount()
    {
        return apps_.size();
    }

    @Override
    public Object getItem(int position)
    {
        return null;
    }

    @Override
    public long getItemId(int position)
    {
        //return apps_.get(position).hashCode();
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        final App app = apps_.get(position);
        
        View v = convertView;
        if (v == null)
            v = layoutInflater_.inflate(R.layout.item, parent, false);

        TextView labelv = (TextView) v.findViewById(R.id.label);
        if (labelv != null)
        {
            labelv.setText(app.info_.label_);
            //labelv.setText(app.ai_.name);
        }
        
        ImageView iconv = (ImageView) v.findViewById(R.id.icon);
        if (iconv != null)
        {
            iconv.setImageDrawable(app.img_);
            if (app.img_ == null)
                new DrawableLoaderAsyncTask(iconv).execute(app);
            else
                Set(iconv, null);
        }
        
        return v;
    }
    
    public boolean doAction(Context context, int action, int position)
    {
        try
        {
            final App app = apps_.get(position);
            //toast(context, "Do: " + Integer.toHexString(app.ai_.flags));
            switch (action)
            {
            case R.id.action_open:
                open(context, pm_.getLaunchIntentForPackage(app.getPackageName()));
                break;

            case R.id.action_info:
                openInfo(context, app.getPackageName());
                break;

            case R.id.action_appstore:
                openAppStore(context, app.getPackageName());
                break;

            default:
                return false;
            }

            return true;
        }
        catch (android.content.ActivityNotFoundException e)
        {
            e.printStackTrace();
            toast(context, "Unable to launch activity.");
            return false;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            toast(context, "Unknown exception: " + e);
            return false;
        }
    }
    
    void open(Context context, Intent i)
    {
        if (i != null)
            context.startActivity(i);
        else
            toast(context, "No launch activity.");
    }
    
    void openInfo(Context context, String packageName)
    {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setData(Uri.parse("package:" + packageName));
        context.startActivity(i);
    }
    
    void openAppStore(Context context, String packageName)
    {
        final String installer = pm_.getInstallerPackageName(packageName);
        String url = null;
        if (installer == null || installer.isEmpty())
            toast(context, "No installer registered for this app.");
        else if ("com.android.vending".equals(installer))
            url = "market://details?id=" + packageName;
        else if ("com.amazon.venezia".equals(installer))
            url = "amzn://apps/android?p=" + packageName;
        else
            toast(context, "Unknown installer: " + installer);
        if (url != null)
        {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setData(Uri.parse(url));
            context.startActivity(i);
        }
    }

    static void toast(Context context, String msg)
    {
        Toast toast = Toast.makeText(context, msg, Toast.LENGTH_LONG);
        toast.show();
    }
}
