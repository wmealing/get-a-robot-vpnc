package org.codeandroid.vpnc_frontend;

import java.util.List;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AdapterView.AdapterContextMenuInfo;

public class VPNC extends PreferenceActivity implements OnPreferenceClickListener
{

	private ProgressCategory networkList;
	private CheckBoxPreference vpnEnabled;
	private boolean notificationEnabled;

	private VpncProcessHandler vpncHandler;
	private ServiceConnection serviceConnection = getServiceConnection();
	private MonitorService monitorService;
	private Intent monitorIntent = new Intent( MonitorServiceImpl.class.getName() );
	private Handler handler = new Handler();
	private ProgressDialog progressDialog;
	private Dialog passwordDialog;
	private int connectedVpnId;

	private final int SUB_ACTIVITY_REQUEST_CODE = 1;

	private final int VPN_ENABLE = 1;
	private final int VPN_NOTIFICATIONS = 2;
	private final int ADD_NETWORK = 4;
	private static final int DIALOG_FLAGS = 8;

	private final String VPN_ENABLE_KEY = "VPN";
	private final String VPN_NOTIFICATIONS_KEY = "NOTIFICATION";
	private final String ADD_NETWORK_KEY = "ADD_NETWORK";
	private final static String VPNC_FLAGS_KEY = "vpncFlags";

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate( savedInstanceState );
		passwordDialog = new Dialog( this );
		connectedVpnId = getSharedPreferences( "vpnc", MODE_PRIVATE ).getInt( "connectedVpnId", -1 );
		vpncHandler = new VpncProcessHandler();
		if( connectedVpnId != -1 )
		{
			if( vpncHandler.isConnected() )
			{
				Util.debug( "Last saved state indicates that we're connected to connection #" + connectedVpnId );
			}
			else
			{
				//maybe the phone has been restarted or the vpnc process killed some other how
				connectedVpnId = -1;
				saveConnectedVpnId();
			}
		}

		addPreferencesFromResource( R.xml.vpnc_settings );

		// Copy files to their locations, we should perhaps do it on the first run, of this version.
		final Intent intent = new Intent( this, BackendFileManager.class );
		startActivityForResult( intent, SUB_ACTIVITY_REQUEST_CODE );

		vpnEnabled = (CheckBoxPreference)findPreference( "VPN" );
		vpnEnabled.setOnPreferenceChangeListener( getVPNActivationListener() );

		CheckBoxPreference notificationCheckBox = (CheckBoxPreference)findPreference( "NOTIFICATION" );
		notificationCheckBox.setOnPreferenceChangeListener( getNotificationActivationListener() );
		notificationEnabled = notificationCheckBox.isChecked();

		networkList = (ProgressCategory)findPreference( "network_list" );
		networkList.setOnPreferenceClickListener( this );

		Preference addNew = (Preference)findPreference( "ADD_NETWORK" );
		addNew.setOnPreferenceClickListener( this );

		getListView().setOnCreateContextMenuListener( createContextMenuListener );
		ShowNetworks();
		
		String referrer = getIntent().getStringExtra( MonitorServiceImpl.class.getName() + ".referrer" );
		if( MonitorServiceImpl.class.getName().equals(referrer) )
		{
			offerReconnect();
		}
	}

	@Override
	protected void onPause()
	{
		saveConnectedVpnId();
		super.onPause();
	}
	
	@Override
	protected void onResume()
	{
		super.onResume();
		if( connectedVpnId != -1 && Util.getProcessId() <= 0 )
		{
			//Looks like we may have recently lost our connection
			Util.debug( "Connection must have dropped" );
			connectedVpnId = -1;
			saveConnectedVpnId();
			ShowNetworks();
		}
	}

	private void saveConnectedVpnId()
	{
		Editor editor = getSharedPreferences( "vpnc", MODE_PRIVATE ).edit();
		editor.putInt( "connectedVpnId", connectedVpnId );
		editor.commit();
		if( monitorService != null && connectedVpnId == -1 )
		{
			//Service is running, but shouldn't
			stopMonitorService();
		}
		else if( monitorService == null )
		{
			//Service isn't running but maybe it should be
			startMonitorService();
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig)
	{
		Util.debug( "Configuration Changed. I'd rather handle it myself (and ignore it for now) than have the OS blow up all I've got in the middle of a connection!" );
		super.onConfigurationChanged( newConfig );
	}

	private OnCreateContextMenuListener createContextMenuListener = new OnCreateContextMenuListener()
	{

		public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo)
		{
			if( menuInfo instanceof AdapterContextMenuInfo )
			{
				AdapterContextMenuInfo adapterInfo = (AdapterContextMenuInfo)menuInfo;

				switch( (int)adapterInfo.id )
				{
					case VPN_ENABLE:
						Util.debug( "long press handler skipping vpn checkbox" );
						break;
					case VPN_NOTIFICATIONS:
						Util.debug( "long press handler skipping notifications checkbox" );
						break;

					case ADD_NETWORK:
						Util.debug( "long press handler skipping add button" );
						break;

					default:
						Util.debug( "long press handler, handling the choice" );
						menu.add( Menu.NONE, 0, 0, R.string.connect );
						menu.add( Menu.NONE, 1, 1, R.string.disconnect );
						menu.add( Menu.NONE, 2, 2, R.string.edit );
				}
			}
		}
	};

	@Override
	public boolean onContextItemSelected(MenuItem item)
	{
		if( item.getMenuInfo() instanceof AdapterContextMenuInfo )
		{
			AdapterContextMenuInfo menuInfo = (AdapterContextMenuInfo)item.getMenuInfo();
			final NetworkPreference networkPreference = (NetworkPreference)networkList.getPreference( menuInfo.position - 3 );

			switch( item.getItemId() )
			{
				case 0:
					final NetworkConnectionInfo info = NetworkDatabase.getNetworkDatabase( this ).singleNetwork( networkPreference._id );
					progressDialog = ProgressDialog.show( this, getString( R.string.please_wait ), getString( R.string.connecting ) );
					Thread thread = new Thread()
					{

						@Override
						public void run()
						{
							vpncHandler.connect( VPNC.this, info );
						}
					};
					thread.start();
					break;

				case 1:
					//if( connectedVpnId == networkPreference._id )
					final ProgressDialog disconnectProgressDialog = ProgressDialog.show( this, getString( R.string.please_wait ),
							getString( R.string.disconnecting ) );
					Thread disconnectThread = new Thread()
					{

						@Override
						public void run()
						{
							vpncHandler.disconnect();
							Runnable uiTask = new Runnable()
							{

								public void run()
								{
									if( connectedVpnId != -1 )
									{
										connectedVpnId = -1;
										saveConnectedVpnId();
									}
									networkPreference.refreshNetworkState();
									disconnectProgressDialog.dismiss();
								}
							};
							handler.post( uiTask );
						}
					};
					disconnectThread.start();
					break;

				case 2:
					Intent intent = new Intent( this, EditNetwork.class );
					intent.putExtra( Intent.EXTRA_TITLE, networkPreference.getid() );
					startActivityForResult( intent, SUB_ACTIVITY_REQUEST_CODE );

				default:
					break;
			}
		}
		return false;
	}

	public void setConnected(final boolean connected, final NetworkConnectionInfo info)
	{
		NetworkPreference networkPreferenceFound = null;
		for( int index = 0; index < networkList.getPreferenceCount() && networkPreferenceFound == null; index++ )
		{
			NetworkPreference candidate = (NetworkPreference)networkList.getPreference( index );
			if( candidate._id == info.getId() )
			{
				networkPreferenceFound = candidate;
			}
		}
		final NetworkPreference networkPreference = networkPreferenceFound;

		//The network list shouldn't have changed so I'm banking on networkPreference not being null now
		Runnable uiTask = new Runnable()
		{

			public void run()
			{
				if( connected )
				{
					long timestamp = System.currentTimeMillis();
					info.setLastConnect( timestamp );
					NetworkDatabase.getNetworkDatabase( VPNC.this ).updateNetwork( info );
					connectedVpnId = info.getId();
					saveConnectedVpnId();
					networkPreference.setLastConnect( timestamp );
					networkPreference.setSummary( R.string.connected );
				}
				else
				{
					networkPreference.setSummary( R.string.failed_connect );
				}
				progressDialog.dismiss();
			}
		};
		handler.post( uiTask );
	}

	public void getPassword( boolean numericToken )
	{
		Util.debug( "Password is " + (numericToken?"numeric":"text") );
		if( numericToken )
		{
			passwordDialog.setContentView( R.layout.numeric_password_dialog );
		}
		else
		{
			passwordDialog.setContentView( R.layout.text_password_dialog );
		}
		passwordDialog.setTitle( R.string.please_type_password );
		Button okButton = (Button)passwordDialog.findViewById( R.id.okPasswordButton );
		okButton.setOnClickListener( okPasswordOnClickListener );
		passwordDialog.show();
	}

	private OnClickListener okPasswordOnClickListener = new OnClickListener()
	{

		public void onClick(View view)
		{
			final String password = ((EditText)passwordDialog.findViewById(R.id.passwordEditText)).getText().toString();
			passwordDialog.dismiss();
			Thread thread = new Thread()
			{

				@Override
				public void run()
				{
					vpncHandler.continueConnection( VPNC.this, password );
				}
			};
			thread.start();
		}
	};

	/* I've been told that i should change this to use an adapter */
	private void ShowNetworks()
	{

		/* Remove all the networks, this is a full refresh */
		networkList.removeAll();

		NetworkDatabase n = NetworkDatabase.getNetworkDatabase( this );
		List<NetworkConnectionInfo> connectionInfos = n.allNetworks();
		for( NetworkConnectionInfo connectionInfo : connectionInfos )
		{
			NetworkPreference pref = new NetworkPreference( this, null, connectionInfo );
			Util.debug( "Adding NetworkPreference with ID:" + connectionInfo.getId() );
			networkList.addPreference( pref );
			if( pref._id == connectedVpnId )
			{
				pref.setSummary( R.string.connected );
			}
		}
	}

	public boolean onPreferenceClick(Preference preference)
	{

		String key = preference.getKey();

		if( key.equals( VPN_ENABLE_KEY ) )
		{
			Util.debug( "on preference click handling vpn checkbox" );
		}
		else if( key.equals( VPN_NOTIFICATIONS_KEY ) )
		{
			Util.debug( "on preference click handling notifications checkbox" );
		}
		else if( key.equals( ADD_NETWORK_KEY ) )
		{
			Util.debug( "on preference click handling add button" );
			Intent intent = new Intent( this, EditNetwork.class );
			intent.putExtra( Intent.EXTRA_TITLE, -1 );
			startActivityForResult( intent, SUB_ACTIVITY_REQUEST_CODE );
			Util.debug( "ROAAAAAAAAAAAR!" );
		}
		else
		{
			Util.debug( "dont care about the other preferences" );
		}

		// We should only handle a few cases here, not everything.
		return true;
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		super.onActivityResult( requestCode, resultCode, data );
		Util.debug( "On Activity Result: resultcode " + resultCode );
		ShowNetworks();
	}

	private OnPreferenceChangeListener getVPNActivationListener()
	{
		return new OnPreferenceChangeListener()
		{

			public boolean onPreferenceChange(Preference preference, Object newValue)
			{
				if( Boolean.TRUE.equals( newValue ) )
				{
					//nothing to do
				}
				else
				{
					vpncHandler.disconnect();
					if( connectedVpnId != -1 )
					{
						connectedVpnId = -1;
						saveConnectedVpnId();
					}
				}
				return true;
			}
		};
	}

	private OnPreferenceChangeListener getNotificationActivationListener()
	{
		return new OnPreferenceChangeListener()
		{
			public boolean onPreferenceChange(Preference preference, Object newValue)
			{
				if( Boolean.TRUE.equals(newValue) )
				{
					Util.debug( "Notification option enabled" );
					notificationEnabled = true;
					startMonitorService();
				}
				else
				{
					Util.debug( "Notification option disabled" );
					notificationEnabled = false;
					stopMonitorService();
				}
				return true;
			}
		};
	}
	
	@Override
	protected void onNewIntent(Intent intent)
	{
		Util.debug( "onNewIntenet" );
		setIntent(intent);
		String referrer = getIntent().getStringExtra( MonitorServiceImpl.class.getName() + ".referrer" );
		if( MonitorServiceImpl.class.getName().equals(referrer) )
		{
			offerReconnect();
		}
	}

	public Handler getHandler()
	{
		return handler;
	}

	private ServiceConnection getServiceConnection()
	{
		return new ServiceConnection()
		{
			public void onServiceConnected(ComponentName name, IBinder service)
			{
				Util.debug( "Connected to monitor service" );
				monitorService = MonitorService.Stub.asInterface(service);
				try
				{
					monitorService.startMonitor();
				}
				catch( RemoteException e )
				{
					Util.error( "Failed to start monitor service", e );
				}
			}
			public void onServiceDisconnected(ComponentName name)
			{
				Util.debug( "oops, disconnected!" );
				monitorService = null;
			}
		};
	}

	private void startMonitorService()
	{
		if( notificationEnabled && connectedVpnId != -1 )
		{
			Util.debug( "Monitoring is enabled and we should be connected to vpn connection #" + connectedVpnId );
			Util.debug( "Will connect to service" );
			
			// Call start service first so the service lifecycle isn't tied to this activity
			startService( monitorIntent );
			if( monitorService == null )
			{
				serviceConnection = getServiceConnection();
			}
			bindService( monitorIntent, serviceConnection, Context.BIND_AUTO_CREATE );
		}
		else
		{
			Util.debug( "Monitoring will not start" );
		}
	}

	private void stopMonitorService()
	{
		if( monitorService != null )
		{
			Util.debug( "Will disconnect service" );
			try
			{
				monitorService.stopMonitor();
				monitorService = null;
			}
			catch( RemoteException e )
			{
				Util.error( "Failed to stop monitoring service" );
			}
		}
	}
	
	private void offerReconnect()
	{
		int lastVpnId = getIntent().getExtras().getInt( MonitorServiceImpl.class.getName() + ".vpnId" );
		final NetworkConnectionInfo info = NetworkDatabase.getNetworkDatabase(this).singleNetwork(lastVpnId);
		Builder builder = new Builder(this);
		builder.setTitle(R.string.reconnect_title);
		builder.setCancelable(true).setNegativeButton( getString(R.string.cancel), null );
		builder.setMessage( getString(R.string.reconnect_question) + " " + info.getNetworkName() + "?" );
		android.content.DialogInterface.OnClickListener listener = new android.content.DialogInterface.OnClickListener()
		{
			@Override
			public void onClick(DialogInterface dialog, int which)
			{
				progressDialog = ProgressDialog.show( VPNC.this, getString( R.string.please_wait ), getString( R.string.connecting ) );
				Thread thread = new Thread()
				{
					@Override
					public void run()
					{
						vpncHandler.connect( VPNC.this, info );
					}
				};
				thread.start();
			}
		};
		builder.setPositiveButton( R.string.ok, listener );
		builder.create().show();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		menu.add(Menu.NONE, 0, 0, R.string.vpnc_flags_option).setIcon(android.R.drawable.ic_menu_preferences);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		int id = item.getItemId();
		if( id == 0 )
		{
			showDialog(DIALOG_FLAGS);
		}
		else
		{
			throw new IllegalStateException("Context Menu selection of " + id + " not expected.");
		}
		return false;
	}
	
	@Override
	protected Dialog onCreateDialog(int dialogId)
	{
		if( dialogId == DIALOG_FLAGS )
		{
			Dialog dialog = new Dialog(this);
			dialog.setContentView(R.layout.flags_dialog);
			dialog.setTitle(R.string.vpnc_flags_prompt);
			EditText flagsEditText = (EditText)dialog.findViewById(R.id.flagsEditText);
			flagsEditText.setText( getExtraFlags() );
			flagsEditText.selectAll();
			
			OnClickListener okListener = new OnClickListener()
			{
				public void onClick(View view)
				{
					EditText flagsEditText = (EditText)view.getRootView().findViewById(R.id.flagsEditText);
					Editor preferenceEditor = getSharedPreferences("vpnc", MODE_PRIVATE).edit();
					preferenceEditor.putString(VPNC_FLAGS_KEY, flagsEditText.getText().toString());
					preferenceEditor.commit();
					dismissDialog(DIALOG_FLAGS);
				}
			};
			Button okButton = (Button)dialog.findViewById(R.id.flagsOkButton);
			okButton.setOnClickListener(okListener);
			
			OnClickListener cancelListener = new OnClickListener()
			{
				public void onClick(View view)
				{
					dismissDialog(DIALOG_FLAGS);
				}
			};
			Button cancelButton = (Button)dialog.findViewById(R.id.flagsCancelButton);
			cancelButton.setOnClickListener(cancelListener);
			
			return dialog;
		}
		else
		{
			throw new IllegalStateException("Creation of dialog id #" + dialogId + " not expected.");
		}
	}
	
	String getExtraFlags()
	{
		return getSharedPreferences("vpnc", MODE_PRIVATE).getString(VPNC_FLAGS_KEY, "");
	}
}
