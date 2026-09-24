// Official service account payment flow; extracted without identity values.

function continueSubmit(type) {
        if (daySel === '' || halfSel === '' || hourSel === '') {
            return;
        }
        var dayData = regisInfo.dayViews[daySel];
        var halfData = dayData.registryList[halfSel];
        var hourData = halfData.regHourList[hourSel][0];
        if (!halfData || !hourData) {
            return;
        }

        if (halfData.iscanceled == '1' && type != '1') {
            hbgh();
            return;
        }

        var userId = $("#userId").val();
        var userIdKey =  localStorage.getItem(userId);

        //白名单控制(用于线上测试时控制用户请求)
        var isOver = true;
        var functionInfo = {
            ptno:$("#ptno").val(),
            ptnoKey:getPtnoKey(),
            functionid:"002"
        }
        $.ajax({
            url:"/function/functionControl",
            type: "POST",
            contentType: "application/json",
            dataType: "JSON",
            async:false,
            data: JSON.stringify(functionInfo),
            error:function (){
                mui.alert("服务器繁忙，请稍候再试！");
                return;
            },
            success:function (obj){
                console.info(obj);
                if (obj == null){
                    mui.alert("未能正确获取权限信息，请稍后重试");
                    return;
                }
                if (obj.code == "0"){
                    isOver = false;
                }else if (obj.code == "-2" || obj.code == "-1"){
                    mui.alert("系统正在升级中，请您稍后再来，感谢您的理解！");
                    return;
                }else {
                    mui.alert(obj.msg);
                    return;
                }
            }
        })

        if (isOver){
            //结束js函数
            return;
        }

        var obj = new Object();
        obj.userId = userId;
        obj.userIdKey = userIdKey;
        obj.dept_code1 = $("#deptCode1").val();
        obj.dept_code2 = $("#deptCode2").val();
        obj.purpose = $("#purpose").val();
        obj.dept_nm1 = $("#deptNm1").val();
        obj.dept_nm2 = $("#deptNm2").val();

        obj.reg_date = dayData.day;
        obj.reg_half = halfData.reg_half;
        obj.reg_hour = hourData;
        obj.doctor_code = halfData.doctor_code;
        obj.title_type = halfData.title_type;

        //增加字段 是否候补号源
        obj.iscanceled = halfData.iscanceled;

        obj.doctor = halfData.doctor;
        obj.dept_code2_from = $("#deptCode2From").val();

        var _daySel = daySel;
        var _halfSel = halfSel;
        var _hourSel = hourSel;
        cancelModel();
        Notiflix.Loading.Init({});
        Notiflix.Loading.Standard();
        timers = 0;
        $.ajax({
            type: "POST",
            url: "/regis/lockRegis",
            contentType: "application/json",
            dataType: "JSON",
            data: JSON.stringify(obj),
            error: function () {
                Notiflix.Loading.Remove();
                mui.alert("系统错误，请联系管理员！")
            },
            success: function (data) {
                if (data) {
                    if (data.code == -2) {
                        // 无权限，跳转到无权限页面;
                        Notiflix.Loading.Remove();
                        window.location.href ="/nopower2.html";
                    } else if (data.code == 2) {
                        // 当前出诊区段未有号源
                        // 更新号源，并页面更新
                        Notiflix.Loading.Remove();
                        mui.alert(data.name);
                        updQty(_daySel, _halfSel, _hourSel, '0');
                    } else if (data.code == 3) {
                        // 当前就诊时段未有号源
                        Notiflix.Loading.Remove();
                        mui.alert(data.name);
                        halfData.regHourList[_hourSel][1] = '0';
                    } else if (data.code == 0) {
                        // 正常返回，循环访问后台看是否
                        //隔1秒后台查询预约情况，30秒后未返回成功值，则表示预约失败
                        setTimeout(function(){
                            getLockRes(_daySel, _halfSel, _hourSel)
                        }, 500);
                    } else {
                        Notiflix.Loading.Remove();
                        mui.alert(data.name);
                    }
                } else {
                    Notiflix.Loading.Remove();
                    mui.alert("系统错误，请稍后再试！")
                }
            },
            complete: function () {

            }
        });
    }

function getLockRes(_daySel, _halfSel, _hourSel) {
        if (timers >= 30) {
            Notiflix.Loading.Remove();
            mui.alert("预约超时，请稍后重试");
            return;
        }

        var dataRequest = {
            "userId": $("#userId").val()
        }
        jQuery.ajax({
            type: "POST",
            url: "/regis/queryRegisStat",
            crossOrigin: true,
            dataType: "JSON",
            contentType: "application/json",
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            data: JSON.stringify(dataRequest),
            error: function () {
            },
            success: function (data) {
                if (data.code == 0) {
                    quanCount();
                    // 成功后更新页面
                    Notiflix.Loading.Remove();
                    updHalfQty(_daySel, _halfSel, _hourSel, data.data[3]);
                    updHourQty(_daySel, _halfSel, _hourSel, data.data[4]);
                    // Notiflix.Confirm.Init();
                    //成功，付费
                    if (data.data[5] == '1') {
                        // 0元订单
                        Notiflix.Confirm.Init();
                        Notiflix.Confirm.Show('预约成功', "点击确定前往门诊预约结果页面可查看订单详情！温馨提示:来院取号时，请在自助机或收费窗口取号", '确定', '取消', function () {
                            var userId = $("#userId").val();
                            var ptno = $("#ptno").val();
                            var userIdKey = URLencode(localStorage.getItem(userId));
                            window.location.href = "/regis/initRegisList?userId=" + userId + "&userIdKey=" + userIdKey + "&ptno=" + ptno;
                        });
                    } else {
                        console.info(data.data);
                        console.info(data.data[7]);
                        console.info(data.data[9]);
                        var isvo = false;
                        //先判断是否为候补号源
                        if (data.data[9] == '1'){
                            var userId = $("#userId").val();
                            var userIdKey = URLencode(localStorage.getItem(userId));
                            window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                            return;
                        }
                        //判断是否为无偿献血优惠政策享用
                        if (data.data[7] == '1'){
                            console.info(data.data[7]);
                            //是
                            var btnValue = ['取消','放弃优惠,继续']
                            mui.confirm(data.data[8],"温馨提示",btnValue,function (e){
                                if(e.index == 0) {
                                    return;
                                } else{
                                    //白名单控制
                                    var isOver = false;
                                    var functionInfo = {
                                        ptno:$("#ptno").val(),
                                        ptnoKey:getPtnoKey(),
                                        functionid:"001"
                                    }
                                    $.ajax({
                                        url:"/function/functionControl",
                                        type: "POST",
                                        contentType: "application/json",
                                        dataType: "JSON",
                                        async:false,
                                        data: JSON.stringify(functionInfo),
                                        error:function (){
                                            Notiflix.Loading.Remove();
                                            mui.alert("服务器繁忙，请稍候再试！");
                                            return;
                                        },
                                        success:function (obj){
                                            Notiflix.Loading.Remove();
                                            console.info(obj);
                                            if (obj.code == "-2" || obj.code == "-1"){
                                                //医保移动支付未开放 自费支付
                                                isOver = true;
                                                var userId = $("#userId").val();
                                                var userIdKey = URLencode(localStorage.getItem(userId));
                                                window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                                return;
                                            }
                                        }
                                    })

                                    if (isOver){
                                        //结束js函数
                                        return;
                                    }

                                    //判断是否可以医保分解支付（生育险、工伤） 1:需要 则走下方弹框 2：不需要 则直接跳转自费页面
                                    if (data.data[6] == '2'){
                                        var userId = $("#userId").val();
                                        var userIdKey = URLencode(localStorage.getItem(userId));
                                        window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                        return;
                                    }

                                    var btnValue = ['医保移动支付','全额支付']
                                    var alertText = "<p>医院已开启<font style='color: red;'>医保移动支付</font>（含本市职工、居民、超转人员及异地医保患者），选择“医保移动支付”实现医保线上报销，选择“全额支付”将全额支付诊查费，取号时退还报销金额。\n" +
                                        "<font style='color: red;'>公疗、军休、离休、自费、生育险患者请选择“全额支付”。</font>\n" +
                                        "<font style='color: red;'>异地医保患者直结报销需办理异地备案。</font></p>";
                                    var relock = false;
                                    mui.confirm(alertText,"请选择支付方式",btnValue,function (e){
                                        var isYbUpgrade = checkYbUpgradeTime();
                                        if (isYbUpgrade && e.index == 0) {
                                            setTimeout(function() {
                                                mui.alert(alertYbTextSubmit);
                                            }, 300);
                                            return;
                                        }
                                        if(e.index == 0) {
                                            if (!relock){
                                                relock = true;
                                            }
                                            else{
                                                console.info("已锁定");
                                                return;
                                            }
                                            Notiflix.Loading.Init({});
                                            Notiflix.Loading.Standard();
                                            var userId = $("#userId").val();
                                            var userIdKey = URLencode(localStorage.getItem(userId));
                                            var dataInfo = {
                                                userId : userId,
                                                userIdKey : userIdKey,
                                                orderNo : data.data[2]
                                            }
                                            $.ajax({
                                                url:"/order/sxPayCN",
                                                type: "POST",
                                                contentType: "application/json",
                                                dataType: "JSON",
                                                data: JSON.stringify(dataInfo),
                                                error:function (){
                                                    Notiflix.Loading.Remove();
                                                    mui.alert("服务器繁忙，请稍候再试！");
                                                    return;
                                                },
                                                success:function (obj){
                                                    Notiflix.Loading.Remove();
                                                    console.info(obj);
                                                    if (obj.code == "0"){
                                                        sessionStorage.setItem("pathUrl",obj.data);
                                                        console.info("进入设置小程序")
                                                        //成功 根据授权码 跳转小程序
                                                        window.location.href = "/regis/regisYbPayState?userId=" + $("#userId").val() + "&userIdKey=" + getUserIdKey($("#userId").val())
                                                            + "&orderno=" + data.data[2] + "&paytype=1&ptno=" + $("#ptno").val();
                                                    }else{
                                                        mui.alert("服务器繁忙，请稍候再试！");
                                                        return;
                                                    }
                                                }
                                            })
                                        } else{
                                            var userId = $("#userId").val();
                                            var userIdKey = URLencode(localStorage.getItem(userId));
                                            window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                        }
                                    })
                                }
                            })
                        } else {
                            //白名单控制
                            var isOver = false;
                            var functionInfo = {
                                ptno:$("#ptno").val(),
                                ptnoKey:getPtnoKey(),
                                functionid:"001"
                            }
                            $.ajax({
                                url:"/function/functionControl",
                                type: "POST",
                                contentType: "application/json",
                                dataType: "JSON",
                                async:false,
                                data: JSON.stringify(functionInfo),
                                error:function (){
                                    Notiflix.Loading.Remove();
                                    mui.alert("服务器繁忙，请稍候再试！");
                                    return;
                                },
                                success:function (obj){
                                    Notiflix.Loading.Remove();
                                    console.info(obj);
                                    if (obj.code == "-2" || obj.code == "-1"){
                                        //医保移动支付未开放 自费支付
                                        isOver = true;
                                        var userId = $("#userId").val();
                                        var userIdKey = URLencode(localStorage.getItem(userId));
                                        window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                        return;
                                    }
                                }
                            })

                            if (isOver){
                                //结束js函数
                                return;
                            }

                            //判断是否可以医保分解支付（生育险、工伤） 1:需要 则走下方弹框 2：不需要 则直接跳转自费页面
                            if (data.data[6] == '2'){
                                var userId = $("#userId").val();
                                var userIdKey = URLencode(localStorage.getItem(userId));
                                window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                return;
                            }

                            var btnValue = ['医保移动支付','全额支付']
                            var alertText = "<p>医院已开启<font style='color: red;'>医保移动支付</font>（含本市职工、居民、超转人员及异地医保患者），选择“医保移动支付”实现医保线上报销，选择“全额支付”将全额支付诊查费，取号时退还报销金额。\n" +
                                "<font style='color: red;'>公疗、军休、离休、自费、生育险患者请选择“全额支付”。</font>\n" +
                                "<font style='color: red;'>异地医保患者直结报销需办理异地备案。</font></p>";
                            var relock = false;
                            mui.confirm(alertText,"请选择支付方式",btnValue,function (e){
                                var isYbUpgrade = checkYbUpgradeTime();
                                if (isYbUpgrade && e.index == 0) {
                                    mui.alert(alertYbTextSubmit);
                                    return;
                                }
                                if(e.index == 0) {
                                    if (!relock){
                                        relock = true;
                                    }
                                    else{
                                        console.info("已锁定");
                                        return;
                                    }
                                    Notiflix.Loading.Init({});
                                    Notiflix.Loading.Standard();
                                    var userId = $("#userId").val();
                                    var userIdKey = URLencode(localStorage.getItem(userId));
                                    var dataInfo = {
                                        userId : userId,
                                        userIdKey : userIdKey,
                                        orderNo : data.data[2]
                                    }
                                    $.ajax({
                                        url:"/order/sxPayCN",
                                        type: "POST",
                                        contentType: "application/json",
                                        dataType: "JSON",
                                        data: JSON.stringify(dataInfo),
                                        error:function (){
                                            Notiflix.Loading.Remove();
                                            mui.alert("服务器繁忙，请稍候再试！");
                                            return;
                                        },
                                        success:function (obj){
                                            Notiflix.Loading.Remove();
                                            console.info(obj);
                                            if (obj.code == "0"){
                                                sessionStorage.setItem("pathUrl",obj.data);
                                                console.info("进入设置小程序")
                                                //成功 根据授权码 跳转小程序
                                                window.location.href = "/regis/regisYbPayState?userId=" + $("#userId").val() + "&userIdKey=" + getUserIdKey($("#userId").val())
                                                    + "&orderno=" + data.data[2] + "&paytype=1&ptno=" + $("#ptno").val();
                                            }else{
                                                mui.alert("服务器繁忙，请稍候再试！");
                                                return;
                                            }
                                        }
                                    })
                                } else{
                                    var userId = $("#userId").val();
                                    var userIdKey = URLencode(localStorage.getItem(userId));
                                    window.location.href = "/order/regisOrderSend?merOrderId=" + data.data[2] + "&userId=" + userId + "&userIdKey=" + userIdKey;
                                }
                            })
                        }
                    }
                } else if (data.code == 2011) {
                    // 就诊时段无足够的号源
                    Notiflix.Loading.Remove();
                    mui.alert(data.name);
                    // 更新内存中就诊时段的数据
                    updHourQty(_daySel, _halfSel, _hourSel, '0');
                } else if (data.code == "2") {
                    // 等待回复
                    setTimeout(function(){
                        getLockRes(_daySel, _halfSel, _hourSel)
                    }, 1000);
                } else {
                    Notiflix.Loading.Remove();
                    mui.alert(data.name);
                }
            },
            complete: function () {
            }
        });
        timers++;
    }

function sendPay(index, invalidtime) {
        cancelBubble();
        var isvolblooddonation = $("#isvolblooddonation"+index).val();
        var isvo = false;
        if (isvolblooddonation == "1"){
            var afterlocktxt = $("#afterlocktxt" + index).val();
            //判断是否为无偿献血优惠政策享用
                //是
            var btnValue = ['取消','放弃优惠,继续']
            mui.confirm(afterlocktxt,"温馨提示",btnValue,function (e){
                if(e.index == 0) {
                    return;
                } else{
                    if (sendPayBl) {
                        sendPayBl = false;
                        if (invalidtime) {
                            var now = new Date();
                            var expTime = new Date(invalidtime);
                            if (now > expTime) {
                                mui.alert("已超过支付时间!");
                                sendPayBl = true;
                                return;
                            }
                        }
                        var userId = $("#userId").val();
                        var userIdKey = URLencode(localStorage.getItem(userId));
                        window.location.href = "/order/regisOrderSend?merOrderId=" + $("#orderno" + index).val() + "&userId=" + userId + "&userIdKey=" + userIdKey;
                        setTimeout(function(){
                            sendPayBl = true;
                        }, 2000);
                    }
                }
            })
        } else {
            if (sendPayBl) {
                sendPayBl = false;
                if (invalidtime) {
                    var now = new Date();
                    var expTime = new Date(invalidtime);
                    if (now > expTime) {
                        mui.alert("已超过支付时间!");
                        sendPayBl = true;
                        return;
                    }
                }
                var userId = $("#userId").val();
                var userIdKey = URLencode(localStorage.getItem(userId));
                window.location.href = "/order/regisOrderSend?merOrderId=" + $("#orderno" + index).val() + "&userId=" + userId + "&userIdKey=" + userIdKey;
                setTimeout(function(){
                    sendPayBl = true;
                }, 2000);
            }
        }
    }

    // 公众号退费处理
