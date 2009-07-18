package org.codeandroid.vpnc_frontend;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.util.AttributeSet;
import android.util.Log;

public class NetworkPreference extends Preference implements OnPreferenceClickListener
{

	public int _id = -1;

	private Context mycontext;
	private final String TAG = "VPNC - PREFERENCE WINDOW";

	public NetworkPreference(Context context, AttributeSet attrs)
	{
		super( context, attrs );
		mycontext = context;
		setOnPreferenceClickListener( this );
		Log.i( TAG, "Creating new NetworkPreference" );
	}

	public NetworkPreference(Context context, AttributeSet attrs, NetworkConnectionInfo connectionInfo)
	{
		this( context, attrs );
		if( connectionInfo.getLastConnect() == Integer.MAX_VALUE )
		{
			this.setSummary( R.string.never_connected );
		}
		else
		{
			this.setSummary( R.string.never_connected ); //later set actual timestamp
		}

		this.setTitle( connectionInfo.getNetworkName() );
		this.setEnabled( true );
		this._id = connectionInfo.getId();
	}

	public int getid()
	{
		return _id;
	}

	public void refreshNetworkState()
	{
		// FIXME: refresh self state
	}

	public boolean onPreferenceClick(Preference preference)
	{
		Intent myIntent = new Intent( mycontext, EditNetwork.class );
		myIntent.putExtra( Intent.EXTRA_TITLE, _id );
		mycontext.startActivity( myIntent );
		return true;
	}
}
