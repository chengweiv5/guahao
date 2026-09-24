// Official results page status routing and insurance resume function.
function getSelfServListInfo() {
        $(".data-list").empty();
        $("#dataError").hide();

        var obj = new Object();
        obj.ptno = $("#ptno").val();
        obj.ptnoKey = localStorage.getItem(obj.ptno);
        obj.actdate = startDate;
        obj.enddate = endDate;
        $.ajax({
            type: "POST",
            // url: "/selfMach/getSelfMachRegis",
            url: "/regis/getRegisList",
            contentType: "application/json",
            dataType: "JSON",
            data: JSON.stringify(obj),
            error: function () {
                mui.alert("系统错误，请联系管理员！")
            },
            success: function (data) {
                if (data) {
                    if (data.code == 200) {
                        if (data.data && data.data.length > 0) {
                            for (var i = 0; i < data.data.length; i++) {
                                var itemData = data.data[i];
                                // 挂号状态
                                var statusNm = "";
                                if (itemData.status == '1' || itemData.status == '7') {
                                    statusNm = "锁号成功";
                                    if(itemData.iscanceled != '0' && itemData.iscanceled == '1'){
                                        statusNm = "未支付，支付后开始候补排队";
                                    }
                                } else if (itemData.status == '2' || itemData.status == '6') {
                                    statusNm = "已预约"
                                    if (itemData.iscanceled != '0' && itemData.iscanceled == '1'){
                                        statusNm = "候补中";
                                    }
                                } else if (itemData.status == '3') {
                                    statusNm = "已取号"
                                } else if (itemData.status == '4') {
                                    statusNm = "已取消"
                                }

                                // if (itemData.iscanceled != '0' && itemData.iscanceled == '3'){
                                //     statusNm = "候补成功";
                                // } else if (itemData.iscanceled != '0' && itemData.iscanceled == '4'){
                                //     statusNm = "候补失败";
                                // }


                                var item = '<div class="data-item" onClick="toDetail('+ i + ')" id="item' + i + '"><div class="left-content">' +
                                    '<div class="sub-item">挂号科室：' + (itemData.dept == null ? '' : itemData.dept) + '</div>' +
                                    '<div class="sub-item">挂号时间：' + itemData.actdate + '</div>' +
                                    '<div class="sub-item">挂号金额：' + (itemData.fee == null ? '' : itemData.fee) + '</div>' +
                                    '<div class="sub-item" id="statusNm'+ i +'" >挂号状态：' + statusNm + '</div>' +
                                    '<input value="' + itemData.his_reg_no + '" type="hidden" id="hisRegNo' + i + '"/>' +
                                    '<input value="' + itemData.dept + '" type="hidden" id="dept' + i + '"/>' +
                                    '<input value="' + itemData.actdate + '" type="hidden" id="actdate' + i + '"/>' +
                                    '<input value="' + itemData.doctor + '" type="hidden" id="doctor' + i + '"/>' +
                                    '<input value="' + itemData.fee + '" type="hidden" id="fee' + i + '"/>' +
                                    '<input value="' + statusNm + '" type="hidden" id="statusNmVal' + i + '"/>' +
                                    '<input value="' + itemData.ampm + '" type="hidden" id="ampm' + i + '"/>' +
                                    '<input value="' + itemData.reserved_date + '" type="hidden" id="reserved_date' + i + '"/>' +
                                    '<input value="' + itemData.ptno + '" type="hidden" id="ptno' + i + '"/>' +
                                    '<input value="' + itemData.name + '" type="hidden" id="name' + i + '"/>' +
                                    '<input value="' + itemData.sex + '" type="hidden" id="sex' + i + '"/>' +
                                    '<input value="' + itemData.birthday + '" type="hidden" id="birthday' + i + '"/>' +
                                    '<input value="' + itemData.telphone + '" type="hidden" id="telphone' + i + '"/>' +
                                    '<input value="' + itemData.isyb + '" type="hidden" id="isyb' + i + '"/>' +
                                    '<input value="' + itemData.ybfee + '" type="hidden" id="ybfee' + i + '"/>' +
                                    '<input value="' + itemData.personfee + '" type="hidden" id="personfee' + i + '"/>' +
                                    '<input value="' + itemData.zffee + '" type="hidden" id="zffee' + i + '"/>' +
                                    '<input value="' + itemData.orderno + '" type="hidden" id="orderno' + i + '"/>' +
                                    '<input value="' + itemData.isvolblooddonation + '" type="hidden" id="isvolblooddonation' + i + '"/>' +
                                    '<input value="' + itemData.afterlocktxt + '" type="hidden" id="afterlocktxt' + i + '"/>' +
                                    '<input value="' + itemData.iscanceled + '" type="hidden" id="iscanceled' + i + '"/>' ;
                                var refundTypeNm = "";
                                if (itemData.source == '2') {
                                    // 微信公众号线上缴费退费操作
                                    if (itemData.status == '1') {
                                        // 允许退号
                                        // 可以进行缴费操作
                                        // item = '<div class="right-content"><button class="btn-no-click">失效时间:' +
                                        //      + itemData.invalidtime + '</button></div>';
                                        item += '<div class="sub-item">失效时间：' + (itemData.invalidtime == null ? '' : itemData.invalidtime) + '</div>';
                                        if (itemData.iscanceled == null || itemData.iscanceled == undefined || itemData.iscanceled == '0'){
                                            //正常自费号源缴费
                                            item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                                '<button class="btn-class" onclick="sendPay(' + i + ',\'' + itemData.invalidtime + '\')">缴费</button>' +
                                                '<button class="btn-class" style="margin-top: 0.4rem;" onclick="cancellationRegis(' + i + ')">取消锁号</button></div>';
                                        } else if (itemData.iscanceled != null && itemData.iscanceled != undefined && itemData.iscanceled == '1'){
                                            // 候补号源自费缴费
                                            item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                                '<button class="btn-class" onclick="sendPay(' + i + ',\'' + itemData.invalidtime + '\')">候补缴费</button>' +
                                                '<button class="btn-class" style="margin-top: 0.4rem;" onclick="cancellationRegis(' + i + ')">取消锁号</button></div>';
                                        }
                                        refundTypeNm = "未支付";
                                    } else if(itemData.status == '7'){
                                        //医保未支付
                                        item += '<div class="sub-item">失效时间：' + (itemData.invalidtime == null ? '' : itemData.invalidtime) + '</div>';
                                        item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                            '<button class="btn-class" onclick="sendYbPay(' + i + ',\'' + itemData.invalidtime + '\')">医保缴费</button>' +
                                            '<button class="btn-class" style="margin-top: 0.4rem;" onclick="cancellationRegis(' + i + ')">取消锁号</button></div>';
                                        refundTypeNm = "医保未支付";
                                    } else if (itemData.status == '2') {
                                        // 可以进行退费操作
                                        if (itemData.refund_type == '2') {
                                            // 0元订单
                                            item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                                '<button class="btn-class" onclick="cancelZeroRegis(' + i + ')">取消订单</button></div>';
                                            refundTypeNm = "无需支付";
                                        } else {

                                            if (itemData.iscanceled == null || itemData.iscanceled == undefined || itemData.iscanceled == '0'){
                                                //正常自费号源缴费
                                                item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                                    '<button class="btn-class" onclick="cancelRegis(' + i + ')">退费</button></div>';
                                            } else if (itemData.iscanceled != null && itemData.iscanceled != undefined && itemData.iscanceled == '1'){
                                                // 候补号源自费缴费
                                                item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                                    '<button class="btn-class" onclick="cancelRegis(' + i + ')">取消候补</button></div>';
                                            }
                                            refundTypeNm = "已支付";
                                        }
                                    } else if (itemData.status == '6'){
                                        item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                            '<button class="btn-class" onclick="cancelYbRegis(' + i + ')">医保退费</button></div>';
                                        refundTypeNm = "医保已支付";
                                    } else {
                                        var ret = setReFundTypeNm(itemData.refund_type, i);
                                        item += ret[0];
                                        refundTypeNm = ret[1];
                                    }
                                } else {
                                    // 其他渠道退费操作
                                    if (itemData.status == '1') {
                                        item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                            '<button class="btn-no-click">未支付</button></div>';
                                        refundTypeNm = "未支付";
                                    } else if (itemData.status == '2' && itemData.refund_type == '1') {
                                        item += '</div><div class="right-content" id="btnDiv' + i + '">' +
                                            '<button class="btn-class" onclick="cancelSelfMach(' + i + ')">退费</button></div>';
                                        refundTypeNm = "已支付";
                                    } else {
                                        var ret = setReFundTypeNm(itemData.refund_type, i);
                                        item += ret[0];
                                        refundTypeNm = ret[1];
                                    }
                                }
                                item += '<input value="' + refundTypeNm + '" type="hidden" id="refundTypeNm' + i + '"/></div>';
                                $(".data-list").append(item);
                            }
                        } else {
                            $("#dataError").show();
                            $("#dataError").html("当前日期未有挂号记录！");
                        }
                    } else {
                        $("#dataError").show();
                        $("#dataError").html(data.name);
                    }
                } else {
                    // mui.alert("系统错误，请稍后再试！");
                    $("#dataError").show();
                    $("#dataError").html("系统错误，请稍后再试！");
                }
            },
            complete: function () {

            }
        });
    }
function sendYbPay(index, invalidtime){
        //组织冒泡事件
        cancelBubble();
        if (sendPayBl) {
            sendPayBl = false;
            //判断是否超过支付时间
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
            //获取医保支付授权码
            var dataInfo = {
                userId : userId,
                userIdKey : userIdKey,
                orderNo : $("#orderno" + index).val()
            }
            Notiflix.Loading.Init({});
            Notiflix.Loading.Standard();
            $.ajax({
                url:"/order/sxPayCN",
                type: "POST",
                contentType: "application/json",
                dataType: "JSON",
                data: JSON.stringify(dataInfo),
                error:function (){
                    Notiflix.Loading.Remove();
                    mui.alert("系统错误，请稍候再试！");
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
                            + "&orderno=" + $("#orderno" + index).val() + "&paytype=1&ptno=" + $("#ptno").val();
                    }else{
                        mui.alert("系统错误，请稍候再试！");
                        return;
                    }
                }
            })
            setTimeout(function(){
                sendPayBl = true;
            }, 2000);
        }
    }