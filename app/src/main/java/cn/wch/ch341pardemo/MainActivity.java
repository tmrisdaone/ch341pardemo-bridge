package cn.wch.ch341pardemo;

import static cn.wch.ch341pardemo.global.Global.CLOSE_DEVICE;
import static cn.wch.ch341pardemo.global.Global.OPEN_DEVICE;
import static cn.wch.ch341pardemo.global.Global.STREAM_CONFIG_SUCCESS;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import cn.wch.ch341lib.CH341Manager;
import cn.wch.ch341lib.exception.CH341LibException;
import cn.wch.ch341pardemo.databinding.ActivityMainBinding;
import cn.wch.ch341pardemo.global.Global;
import cn.wch.ch341pardemo.util.FormatUtil;
import cn.wch.ch341pardemo.util.LogUtil;
import cn.wch.ch347lib.base.i2c.EEPROM_TYPE;
import cn.wch.ch347lib.callback.IUsbStateChange;
import cn.wch.ch347lib.exception.ChipException;
import cn.wch.ch347lib.exception.NoPermissionException;

public class MainActivity extends AppCompatActivity {

    private  Context context;
    private ActivityMainBinding binding;
    private UsbDevice usbDevice; //当前打开的USB设备
    private boolean isDeviceOpen = false;//设备是否打开
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        context = this;
        setContentView(binding.getRoot());

        initView();
        initVariable();
        setViewClickListener();

    }

    private void initView() {
    initSpinner(binding.streamItem.streamWorkRateSpinner,R.array.work_rate);
    initSpinner(binding.streamItem.spiWorkModeSpinner,R.array.spi_work_mode);
    initSpinner(binding.streamItem.spiDataModeSpinner,R.array.spi_data_mode);
    initSpinner(binding.streamItem.spi.spiChipSelectSpinner,R.array.spi_chip_select);
    initSpinner(binding.streamItem.eeprom.eepromTypeSpinner,R.array.eeprom_type);

    }

    private void initVariable() {
    CH341Manager.getInstance().setUsbStateListener(iUsbStateChange);
    setBtnEnable(false);
    // setSPIBtnEnable(false,false);  // Disabled
    }

    private void setViewClickListener() {
    // Enable device button
    binding.btnOpen.setOnClickListener(view -> {
        if(!isDeviceOpen){
            openDevice();
        }else {
            closeDevice();
        }
    });
    // EPP parallel port data read
    binding.eppItem.eppReadDataBtn.setOnClickListener(view -> {
        handleEppReadData();

    });
    // EPP parallel port data write
    binding.eppItem.eppWriteDataBtn.setOnClickListener(view -> {
        handleEppWriteData();

    });
    // EPP parallel port read/write data clear
    binding.eppItem.eppRwDataClearText.setOnClickListener(view -> {
        binding.eppItem.eppRwDataEdit.setText("");
    });

    // EPP parallel port address read
    binding.eppItem.eppReadAddrBtn.setOnClickListener(view -> {
        handleEppReadAddr();

    });
    // EPP parallel port address write
    binding.eppItem.eppWriteAddrBtn.setOnClickListener(view -> {
        handleEppWriteAddr();

    });
    // EPP parallel port read/write address clear
    binding.eppItem.eppRwAddrClearText.setOnClickListener(view -> {
        binding.eppItem.eppRwAddrEdit.setText("");
    });
    // MEM parallel port data read
    binding.memItem.memReadDataBtn.setOnClickListener(view -> {
        handleMemReadData();

    });
    // MEM parallel port data write
    binding.memItem.memWriteDataBtn.setOnClickListener(view -> {
        handleMemWriteData();

    });
    // MEM parallel port read/write data clear
    binding.memItem.memRwDataClearText.setOnClickListener(view -> {
        binding.memItem.memRwDataEdit.setText("");
    });
    // Serial port config
    binding.streamItem.streamModeConfigBtn.setOnClickListener(view -> {
        handleStreamConfig();
    });
    // SPI read/write
    binding.streamItem.spi.spiRwBtn.setOnClickListener(view -> {
        handleSPIRW();
    });
    // SPI write clear
    binding.streamItem.spi.spiWriteDataClearText.setOnClickListener(view -> {
        binding.streamItem.spi.spiWriteDataEdit.setText("");
    });
    // SPI read clear
    binding.streamItem.spi.spiReadDataClearText.setOnClickListener(view -> {
        binding.streamItem.spi.spiReadDataEdit.setText("");
    });
    // I2C read/write
    binding.streamItem.i2c.i2cRwBtn.setOnClickListener(view -> {
        handleI2CRW();
    });
    // I2C read clear
    binding.streamItem.i2c.i2cReadDataClearText.setOnClickListener(view -> {
        binding.streamItem.i2c.i2cReadDataEdit.setText("");
    });
    // I2C write clear
    binding.streamItem.i2c.i2cWriteDataClearText.setOnClickListener(view -> {
        binding.streamItem.i2c.i2cWriteDataEdit.setText("");
    });
    // EEPROM write
    binding.streamItem.eeprom.eepromWriteBtn.setOnClickListener(view -> {
        handelEEPROMWrite();
    });
    // EEPROM read
    binding.streamItem.eeprom.eepromReadBtn.setOnClickListener(view -> {
        handelEEPROMRead();
    });
    // EEPROM read data clear
    binding.streamItem.eeprom.eepromReadDataClearText.setOnClickListener(view -> {
        binding.streamItem.eeprom.eepromReadDataEdit.setText("");
    });
    // EEPROM write clear
    binding.streamItem.eeprom.eepromWriteDataClearText.setOnClickListener(view -> {
        binding.streamItem.eeprom.eepromWriteDataEdit.setText("");
    });
    // Read GPIO
    binding.gpioItem.readGpioBtn.setOnClickListener(view -> {
        handelReadGpio();
    });
    binding.gpioItem.setGpioBtn.setOnClickListener(view -> {
        handleSetGpio();
    });
    }



    /*****************************************UI***************************************************/

    //使能按钮
    private void setBtnEnable(boolean enable){
        binding.eppItem.eppReadDataBtn.setEnabled(enable);
        binding.eppItem.eppWriteDataBtn.setEnabled(enable);
        binding.eppItem.eppReadAddrBtn.setEnabled(enable);
        binding.eppItem.eppWriteAddrBtn.setEnabled(enable);
        binding.memItem.memReadDataBtn.setEnabled(enable);
        binding.memItem.memWriteDataBtn.setEnabled(enable);
    }
    //使能同步串口按钮
    private void setStreamBtnEnable(boolean enable, boolean isInit){
        binding.streamItem.streamModeConfigBtn.setEnabled(enable);
        binding.streamItem.spi.spiRwBtn.setEnabled(isInit);
        binding.streamItem.i2c.i2cRwBtn.setEnabled(isInit);
        binding.streamItem.eeprom.eepromReadBtn.setEnabled(isInit);
        binding.streamItem.eeprom.eepromWriteBtn.setEnabled(isInit);
    }

    private void initSpinner(Spinner spinner, @ArrayRes int arrayId){
        String[] stringArray = getResources().getStringArray(arrayId);
        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.item_spinner, stringArray);
        arrayAdapter.setDropDownViewResource(R.layout.item_spinner);
        spinner.setAdapter(arrayAdapter);
        spinner.setSelection(0);
    }


    /***************************************call back**********************************************/
    private final IUsbStateChange iUsbStateChange = new IUsbStateChange() {
        @Override
        public void usbDeviceDetach(UsbDevice device) {
            //设备拔出
            if(usbDevice!=null){
                if(device.getVendorId()==usbDevice.getVendorId()
                        && device.getProductId()==usbDevice.getProductId()
                        && device.getDeviceName().equals(usbDevice.getDeviceName())){
                    closeDevice();
                }
            }
        }

        @Override
        public void usbDeviceAttach(UsbDevice device) {

        }

        @Override
        public void usbDevicePermission(UsbDevice usbDevice, boolean b) {
            if(!isDeviceOpen){
                openDevice();
            }
        }
    };
    /*************************************** Handler *********************************************/
    //发送消息处理
    private void sendMessage(int what){
        Message msg = new Message();
        msg.what = what;
        mainHandler.sendMessage(msg);
    }

    Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case OPEN_DEVICE: //USB设备打开
                    setBtnEnable(true);
                    setStreamBtnEnable(true,false);
                    binding.btnOpen.setText(getResources().getString(R.string.close_device));
                    break;
                case CLOSE_DEVICE://USB设备关闭
                    setBtnEnable(false);
                    setStreamBtnEnable(false,false);
                    binding.btnOpen.setText(getResources().getString(R.string.open_device));
                    break;
                case STREAM_CONFIG_SUCCESS: //同步串流模式设置成功
                    setStreamBtnEnable(true,true);
                    break;
            }
            super.handleMessage(msg);
        }
    };



    /***************************************Device API*********************************************/
    //Open device
    private void openDevice(){
        ArrayList<UsbDevice> usbDevices = new ArrayList<>();
        try {
            usbDevices =  CH341Manager.getInstance().enumDevice();
        } catch (CH341LibException e) {
            LogUtil.d("enum device exception:"+e.getMessage());
            showToast("Failed to enumerate devices");
            return;
        }
        if(usbDevices.size()==0){
            showToast("No device found");
            return ;
        }
        if(usbDevices.size()!=1){
            showToast("Only one device supported");
            return ;
        }
        UsbDevice usbDevice = usbDevices.get(0);
        try {
            if (CH341Manager.getInstance().hasPermission(usbDevice)){
                if(!CH341Manager.getInstance().openDevice(usbDevice)){
                    showToast("Failed to open device");
                    return ;
                }
                showToast("Device opened successfully");
                isDeviceOpen = true;
                this.usbDevice=usbDevice;
                sendMessage(OPEN_DEVICE);
                try {
                    startService(new Intent(this, TermuxBridge.class));
                } catch (Exception e) {
                    LogUtil.d("TermuxBridge start failed: " + e.getMessage());
                }
            }else {
                CH341Manager.getInstance().requestPermission(context,usbDevice);//Request permission
            }
        } catch (CH341LibException | NoPermissionException | ChipException e) {
            LogUtil.d("open device exception:"+e.getMessage());
            showToast("Open device exception");
        }
    }


    //Close device
    private void closeDevice() {
        if (!isDeviceOpen ||  this.usbDevice == null){
            return;
        }
        CH341Manager.getInstance().closeDevice(usbDevice);
        isDeviceOpen = false;
        this.usbDevice=null;
        sendMessage(Global.CLOSE_DEVICE);
        try {
            stopService(new Intent(this, TermuxBridge.class));
        } catch (Exception e) {
            LogUtil.d("TermuxBridge stop failed: " + e.getMessage());
        }
    }

    /***************************************EPP API*********************************************/
    private boolean ch341EPPInit(){
        byte mode = 0x00;
        try {
            if (!CH341Manager.getInstance().CH34xSetParaMode(this.usbDevice,mode)){
                return false;
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xSetParaMode exception:"+e.getMessage());
            return false;
        }
        try {
            if (!CH341Manager.getInstance().CH34xInitParallel(this.usbDevice,mode)){
                return false;
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xSetParaMode exception:"+e.getMessage());
            return false;
        }
        return true;
    }

    //EPP read data
    private void handleEppReadData() {
        String readDataLenStr = binding.eppItem.eppRwDataLenEdit.getText().toString();
        int readLen = 0;
        if (readDataLenStr.equals("")){
            showToast("Read length is empty");
            return;
        }
        readLen = FormatUtil.hexToInt(readDataLenStr);
        if (readLen <= 0 || readLen >=0x1000){
            showToast("Read length error");
            return;
        }
        if (!ch341EPPInit()){
            showToast("EPP init failed");
            return;
        }
        byte[] buffer = new byte[readLen];
        try {
            if (CH341Manager.getInstance().CH34xEppReadData(this.usbDevice,buffer,readLen)){
                binding.eppItem.eppRwDataEdit.setText(FormatUtil.bytesToHexString(buffer));
                showToast("Read successful");
            }else {
                showToast("Read failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xEppReadData exception:"+e.getMessage());
            showToast("Read error");
        }
    }

    //EPP write data
    private void handleEppWriteData() {
        String readDataLenStr = binding.eppItem.eppRwDataLenEdit.getText().toString();
        String readDataStr = binding.eppItem.eppRwDataEdit.getText().toString();
        int readLen = 0;
        if (readDataLenStr.equals("") || readDataStr.equals("")){
            showToast("Write length or data is empty");
            return;
        }
        readLen = FormatUtil.hexToInt(readDataLenStr);
        if (readLen <= 0 || readLen >=0x1000){
            showToast("Write length error");
            return;
        }
        if (!readDataStr.matches("([0-9|a-f|A-F]{2})*")){
            showToast("Input does not conform to hex format");
            return;
        }
        if (!ch341EPPInit()){
            showToast("EPP init failed");
            return;
        }
        byte[] data = FormatUtil.hexStringToBytes(readDataStr);
        if (data.length != readLen){
            showToast("Output content length mismatch");
            return;
        }

        try {
            if (CH341Manager.getInstance().CH34xEppWriteData(this.usbDevice,data,readLen)){
                showToast("Write successful");
            }else {
                showToast("Write failed");
            }
        } catch (CH341LibException e) {
            showToast("Write exception");
        }
    }


    //EPP address read
    private void handleEppReadAddr() {
        String readDataLenStr = binding.eppItem.eppRwAddrLenEdit.getText().toString();
        int readLen = 0;
        if (readDataLenStr.equals("")){
            showToast("Read length is empty");
            return;
        }
        readLen = FormatUtil.hexToInt(readDataLenStr);
        if (readLen <= 0 || readLen >=0x1000){
            showToast("Read length error");
            return;
        }
        if (!ch341EPPInit()){
            showToast("EPP init failed");
            return;
        }
        byte[] buffer = new byte[readLen];
        try {
            if (CH341Manager.getInstance().CH34xEppReadAddr(this.usbDevice,buffer,readLen)){
                binding.eppItem.eppRwAddrEdit.setText(FormatUtil.bytesToHexString(buffer));
                showToast("Read successful");
            }else {
                showToast("Read failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xEppReadData exception:"+e.getMessage());
            showToast("Read error");
        }
    }

    //EPP address write
    private void handleEppWriteAddr() {
        String writeAddrLenStr = binding.eppItem.eppRwAddrLenEdit.getText().toString();
        String writeAddrStr = binding.eppItem.eppRwAddrEdit.getText().toString();
        int writeLen = 0;
        if (writeAddrLenStr.equals("") || writeAddrStr.equals("")){
            showToast("Write length or data is empty");
            return;
        }
        writeLen = FormatUtil.hexToInt(writeAddrLenStr);
        if (writeLen <= 0 || writeLen >=0x1000){
            showToast("Write length error");
            return;
        }
        if (!writeAddrStr.matches("([0-9|a-f|A-F]{2})*")){
            showToast("Input does not conform to hex format");
            return;
        }
        if (!ch341EPPInit()){
            showToast("EPP init failed");
            return;
        }
        byte[] data = FormatUtil.hexStringToBytes(writeAddrStr);
        if (data.length != writeLen){
            showToast("Output content length mismatch");
            return;
        }
        try {
            if (CH341Manager.getInstance().CH34xEppWriteAddr(this.usbDevice,data,writeLen)){
                showToast("Write successful");
            }else {
                showToast("Write failed");
            }
        } catch (CH341LibException e) {
            showToast("Write exception");
        }
    }

    /***************************************MEM API*********************************************/
    private boolean ch341MEMInit(){
        try {
            return CH341Manager.getInstance().CH34xInitMEM(usbDevice);
        } catch (CH341LibException e) {
            return false;
        }
    }
    //MEM read data
    private void handleMemReadData() {
        String readDataLenStr = binding.memItem.memRwDataLenEdit.getText().toString();
        int readLen = 0;
        if (readDataLenStr.equals("")){
            showToast("Read length is empty");
            return;
        }
        readLen = FormatUtil.hexToInt(readDataLenStr);
        if (readLen <= 0 || readLen >=0x1000){
            showToast("Read length error");
            return;
        }
        if (!ch341MEMInit()){
            showToast("MEM init failed");
            return;
        }
        byte[] buffer = new byte[readLen];
        try {
            if (CH341Manager.getInstance().CH34xMEMReadData(this.usbDevice,buffer,readLen, (byte) 0x00)){
                binding.memItem.memRwDataEdit.setText(FormatUtil.bytesToHexString(buffer));
                showToast("Read successful");
            }else {
                showToast("Read failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xMEMReadData exception:"+e.getMessage());
            showToast(e.getMessage());
        }
    }

    //MEM write data
    private void handleMemWriteData() {
        String writeDataLenStr = binding.memItem.memRwDataLenEdit.getText().toString();
        String writeDataStr = binding.memItem.memRwDataEdit.getText().toString();
        int writeLen = 0;
        if (writeDataLenStr.equals("") || writeDataStr.equals("")){
            showToast("Write length or data is empty");
            return;
        }
        writeLen = FormatUtil.hexToInt(writeDataLenStr);
        if (writeLen <= 0 || writeLen >=0x1000){
            showToast("Write length error");
            return;
        }
        if (!writeDataStr.matches("([0-9|a-f|A-F]{2})*")){
            showToast("Input does not conform to hex format");
            return;
        }
        if (!ch341MEMInit()){
            showToast("MEM init failed");
            return;
        }
        byte[] data = FormatUtil.hexStringToBytes(writeDataStr);
        if (data.length != writeLen){
            showToast("Output content length mismatch");
            return;
        }
        try {
            if (CH341Manager.getInstance().CH34xMEMWriteData(this.usbDevice,data,writeLen,(byte) 0)){
                showToast("Write successful");
            }else {
                showToast("Write failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xMEMWriteData exception:"+e.getMessage());
            showToast(e.getMessage());
        }
    }

    /****************************************stream API***********************************************/
    //Stream Config
    private void handleStreamConfig(){
        int workRate = binding.streamItem.streamWorkRateSpinner.getSelectedItemPosition();
        int workMode = binding.streamItem.spiWorkModeSpinner.getSelectedItemPosition();
        int dataMode = binding.streamItem.spiDataModeSpinner.getSelectedItemPosition();
        byte mode  = 0x00;
        mode = FormatUtil.byteBitChange(mode,0,workRate & 0x1);
        mode = FormatUtil.byteBitChange(mode,1,(workRate >> 1) & 0x1);
        mode = FormatUtil.byteBitChange(mode,2,workMode);
        mode = FormatUtil.byteBitChange(mode,7,dataMode);
        try {
            if(CH341Manager.getInstance().CH34xSetStream(usbDevice,mode)){
                showToast("Config successful");
            }else {
                showToast("Config failed");
            }
        } catch (CH341LibException e) {
            showToast(e.getMessage());
            return;
        }
        sendMessage(STREAM_CONFIG_SUCCESS);
    }
    private int getSPIChipSelect(){
        byte iChipSelect = 0x00;
        if (binding.streamItem.spi.spiChipSelectCheck.isChecked()){
            iChipSelect = FormatUtil.byteBitChange(iChipSelect,7,1);
        }
        int chipSelect =  binding.streamItem.spi.spiChipSelectSpinner.getSelectedItemPosition();
        iChipSelect =  FormatUtil.byteBitChange(iChipSelect,0,chipSelect&0x1);
        iChipSelect =  FormatUtil.byteBitChange(iChipSelect,1,(chipSelect>>1)&0x1);
        return iChipSelect & 0x000000FF;
    }

    //SPI read/write
    private void handleSPIRW(){
        int iChipSelect = getSPIChipSelect();
        String dataLenStr = binding.streamItem.spi.spiRwDataLenEdit.getText().toString();
        String writeDataStr = binding.streamItem.spi.spiWriteDataEdit.getText().toString();
        int writeLen = 0;
        if (dataLenStr.equals("") || writeDataStr.equals("") ){
            showToast("Read/write length or write data is empty");
            return;
        }
        writeLen = FormatUtil.hexToInt(dataLenStr);
        if (writeLen <= 0){
            showToast("Write length error");
            return;
        }
        if (!writeDataStr.matches("([0-9|a-f|A-F]{2})*")){
            showToast("Input does not conform to hex format");
            return;
        }
        byte[] data = FormatUtil.hexStringToBytes(writeDataStr);
        if (data.length != writeLen){
            showToast("Output content length mismatch");
            return;
        }
        try {
            if (CH341Manager.getInstance().CH34xStreamSPI4(this.usbDevice,iChipSelect,writeLen,data)){
                binding.streamItem.spi.spiReadDataEdit.setText(FormatUtil.bytesToHexString(data));
                showToast("Read/write successful");
            }else {
                showToast("Read/write failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xStreamSPI4 exception:"+e.getMessage());
            showToast(e.getMessage());
        }

    }

    //I2C read/write
    private void handleI2CRW(){
        String writeLenStr = binding.streamItem.i2c.i2cWriteDataLenEdit.getText().toString();
        String writeDataStr = binding.streamItem.i2c.i2cWriteDataEdit.getText().toString();
        String readLenStr = binding.streamItem.i2c.i2cReadDataLenEdit.getText().toString();
        int writeLen = 0;
        int readLen = 0;
        if (!writeLenStr.equals("")){
            writeLen = FormatUtil.hexToInt(writeLenStr);
        }
        if (!readLenStr.equals("")){
            readLen = FormatUtil.hexToInt(readLenStr);
        }
        if (writeLen < 0 || readLen <0){
            showToast("Read/write length error");
            return;
        }
        if(writeLen == 0 &&  readLen == 0 ){
            showToast("Read/write length both empty");
            return;
        }
        byte[] writeData = new byte[writeLen];
        byte[] readData = new byte[readLen];
        if (writeLen > 0 ) {
            if (!writeDataStr.matches("([0-9|a-f|A-F]{2})*")){
                showToast("Input does not conform to hex format");
                return;
            }
            byte[] data = FormatUtil.hexStringToBytes(writeDataStr);
            if (data.length != writeLen){
                showToast("Output content length mismatch");
                return;
            }
            System.arraycopy(data,0,writeData,0,writeLen);
        }

        try {
            if (CH341Manager.getInstance().CH34xStreamI2C(this.usbDevice,writeLen,writeData,readLen,readData)){
                binding.streamItem.i2c.i2cReadDataEdit.setText(FormatUtil.bytesToHexString(readData));
                showToast("I2C read/write successful");
            }else {
                showToast("I2C read/write failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xStreamI2C exception:"+e.getMessage());
            showToast("Read/write exception");
        }
    }

    //EEPROM write
    private void handelEEPROMWrite(){
        String addrStr = binding.streamItem.eeprom.eepromWriteAddrEdit.getText().toString();
        String writeDataLenStr = binding.streamItem.eeprom.eepromWriteDataLenEdit.getText().toString();
        String writeDataStr = binding.streamItem.eeprom.eepromWriteDataEdit.getText().toString();
        int writeLen = 0;
        int addr = 0;
        if (writeDataLenStr.equals("") || writeDataStr.equals("") || addrStr.equals("") ){
            showToast("Some write params are empty");
            return;
        }
        addr = FormatUtil.hexToInt(addrStr);
        if (addr < 0 ){
            showToast("Address error");
            return;
        }
        writeLen = FormatUtil.hexToInt(writeDataLenStr);
        if (writeLen <= 0 || writeLen >=0x400){
            showToast("Write length error");
            return;
        }
        if (!writeDataStr.matches("([0-9|a-f|A-F]{2})*")){
            showToast("Input does not conform to hex format");
            return;
        }
        byte[] data = FormatUtil.hexStringToBytes(writeDataStr);
        if (data.length != writeLen){
            showToast("Output content length mismatch");
            return;
        }
        String typeStr = binding.streamItem.eeprom.eepromTypeSpinner.getSelectedItem().toString();
        EEPROM_TYPE eepromType = EEPROM_TYPE.valueOf(typeStr);
        try {
            if (CH341Manager.getInstance().CH34xWriteEEPROM(this.usbDevice,eepromType,addr,writeLen,data)){
                showToast("Write successful");
            }else {
                showToast("Write failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xWriteEEPROM exception:"+e.getMessage());
            showToast(e.getMessage());
        }
    }
    //EEPROM read
    private void handelEEPROMRead(){
        String addrStr = binding.streamItem.eeprom.eepromReadAddrEdit.getText().toString();
        String readLenStr = binding.streamItem.eeprom.eepromReadDataLenEdit.getText().toString();
        int readLen = 0;
        int addr = 0;
        if (readLenStr.equals("") || addrStr.equals("") ){
            showToast("Some params are empty");
            return;
        }
        addr = FormatUtil.hexToInt(addrStr);
        if (addr < 0 ){
            showToast("Address error");
            return;
        }
        readLen = FormatUtil.hexToInt(readLenStr);
        if (readLen <= 0 || readLen >=0x400){
            showToast("Read length error");
            return;
        }
        byte[] data = new byte[readLen];

        String typeStr = binding.streamItem.eeprom.eepromTypeSpinner.getSelectedItem().toString();
        EEPROM_TYPE eepromType = EEPROM_TYPE.valueOf(typeStr);
        try {
            if (CH341Manager.getInstance().CH34xReadEEPROM(this.usbDevice,eepromType,addr,readLen,data)){
                binding.streamItem.eeprom.eepromReadDataEdit.setText(FormatUtil.bytesToHexString(data));
                showToast("Read successful");
            }else {
                showToast("Read failed");
            }
        } catch (CH341LibException e) {
            LogUtil.d("CH34xReadEEPROM exception:"+e.getMessage());
            showToast(e.getMessage());
        }
    }

    /****************************************GPIO API***********************************************/

    //Read GPIO
    private void handelReadGpio() {
        int gpioValue = 0;
        try {
            gpioValue = CH341Manager.getInstance().CH34xGetInput(usbDevice);
        } catch (CH341LibException e) {
            showToast(e.getMessage());
            return;
        }
        // D0-D15, ERR, PEMP, INT, SLCT, BUSY, AUTOFD, SLCTIN, RESET, WRITE, SCL
        binding.gpioItem.cb6.setChecked((gpioValue & 0x00000001)!=0);
        binding.gpioItem.cb7.setChecked((gpioValue & 0x00000002)!=0);
        binding.gpioItem.cb8.setChecked((gpioValue & 0x00000004)!=0);
        binding.gpioItem.cb9.setChecked((gpioValue & 0x00000008)!=0);
        binding.gpioItem.cb10.setChecked((gpioValue & 0x00000010)!=0);
        binding.gpioItem.cb11.setChecked((gpioValue & 0x00000020)!=0);
        binding.gpioItem.cb12.setChecked((gpioValue & 0x00000040)!=0);
        binding.gpioItem.cb13.setChecked((gpioValue & 0x00000080)!=0);
        binding.gpioItem.cb2.setChecked((gpioValue & 0x00000100)!=0);
        binding.gpioItem.cb3.setChecked((gpioValue & 0x00000200)!=0);
        binding.gpioItem.cb4.setChecked((gpioValue & 0x00000400)!=0);
        binding.gpioItem.cb5.setChecked((gpioValue & 0x00000800)!=0);
        binding.gpioItem.cb14.setChecked((gpioValue & 0x00002000)!=0);
        binding.gpioItem.cb1.setChecked((gpioValue & 0x00004000)!=0);
        binding.gpioItem.cb0.setChecked((gpioValue & 0x00008000)!=0);
        binding.gpioItem.cb15.setChecked((gpioValue & 0x00800000)!=0);
    }

    //Set GPIO
    private void handleSetGpio() {
        int gpioDir=0,gpioVal=0;

        //D0
        gpioDir|=(binding.gpioItem.rbOut6.isChecked()?0x00000001:0x00);
        gpioVal|=(binding.gpioItem.cb6.isChecked()?0x00000001:0x00);
        //D1
        gpioDir|=(binding.gpioItem.rbOut7.isChecked()?0x00000002:0x00);
        gpioVal|=(binding.gpioItem.cb7.isChecked()?0x00000002:0x00);
        //D2
        gpioDir|=(binding.gpioItem.rbOut8.isChecked()?0x00000004:0x00);
        gpioVal|=(binding.gpioItem.cb8.isChecked()?0x00000004:0x00);
        //D3
        gpioDir|=(binding.gpioItem.rbOut9.isChecked()?0x00000008:0x00);
        gpioVal|=(binding.gpioItem.cb9.isChecked()?0x00000008:0x00);

        //D4
        gpioDir|=(binding.gpioItem.rbOut10.isChecked()?0x00000010:0x00);
        gpioVal|=(binding.gpioItem.cb10.isChecked()?0x00000010:0x00);
        //D5
        gpioDir|=(binding.gpioItem.rbOut11.isChecked()?0x00000020:0x00);
        gpioVal|=(binding.gpioItem.cb11.isChecked()?0x00000020:0x00);
        //D6
        gpioDir|=(binding.gpioItem.rbOut12.isChecked()?0x00000040:0x00);
        gpioVal|=(binding.gpioItem.cb12.isChecked()?0x00000040:0x00);
        //D7
        gpioDir|=(binding.gpioItem.rbOut13.isChecked()?0x00000080:0x00);
        gpioVal|=(binding.gpioItem.cb13.isChecked()?0x00000080:0x00);

        //ERR
        gpioDir|=(binding.gpioItem.rbOut2.isChecked()?0x00000100:0x00);
        gpioVal|=(binding.gpioItem.cb2.isChecked()?0x00000100:0x00);
        //PEMP
        gpioDir|=(binding.gpioItem.rbOut3.isChecked()?0x00000200:0x00);
        gpioVal|=(binding.gpioItem.cb3.isChecked()?0x00000200:0x00);
        //INT
        gpioDir|=(binding.gpioItem.rbOut4.isChecked()?0x00000400:0x00);
        gpioVal|=(binding.gpioItem.cb4.isChecked()?0x00000400:0x00);
        //SLCT
        gpioDir|=(binding.gpioItem.rbOut5.isChecked()?0x00000800:0x00);
        gpioVal|=(binding.gpioItem.cb5.isChecked()?0x00000800:0x00);

        //BUSY
        gpioDir|=(binding.gpioItem.rbOut14.isChecked()?0x00002000:0x00);
        gpioVal|=(binding.gpioItem.cb14.isChecked()?0x00002000:0x00);

        //AUTOFD
        gpioDir|=(binding.gpioItem.rbOut1.isChecked()?0x00004000:0x00);
        gpioVal|=(binding.gpioItem.cb1.isChecked()?0x00004000:0x00);

        //SLCTIN
        gpioDir|=(binding.gpioItem.rbOut0.isChecked()?0x00008000:0x00);
        gpioVal|=(binding.gpioItem.cb0.isChecked()?0x00008000:0x00);

        //RESET
        gpioDir|=(0x00010000);
        gpioVal|=(binding.gpioItem.cb18.isChecked()?0x00010000:0x00);

        //WRITE
        gpioDir|=(0x00020000);
        gpioVal|=(binding.gpioItem.cb17.isChecked()?0x00020000:0x00);

        //SCL
        gpioDir|=(0x00040000);
        gpioVal|=(binding.gpioItem.cb16.isChecked()?0x00040000:0x00);
        try {
            boolean b = CH341Manager.getInstance().CH34xSetOutput(usbDevice,0x1F,gpioDir,gpioVal);
            showToast(b?"Config successful":"Config failed");
        } catch (CH341LibException e) {
            showToast(e.getMessage());
        }
    }



    private void showToast(String message){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this,message,Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CH341Manager.getInstance().close(this);
    }
}