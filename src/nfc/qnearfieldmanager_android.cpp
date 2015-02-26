/****************************************************************************
**
** Copyright (C) 2013 Centria research and development
** Contact: http://www.qt.io/licensing/
**
** This file is part of the QtNfc module of the Qt Toolkit.
**
** $QT_BEGIN_LICENSE:LGPL21$
** Commercial License Usage
** Licensees holding valid commercial Qt licenses may use this file in
** accordance with the commercial license agreement provided with the
** Software or, alternatively, in accordance with the terms contained in
** a written agreement between you and The Qt Company. For licensing terms
** and conditions see http://www.qt.io/terms-conditions. For further
** information use the contact form at http://www.qt.io/contact-us.
**
** GNU Lesser General Public License Usage
** Alternatively, this file may be used under the terms of the GNU Lesser
** General Public License version 2.1 or version 3 as published by the Free
** Software Foundation and appearing in the file LICENSE.LGPLv21 and
** LICENSE.LGPLv3 included in the packaging of this file. Please review the
** following information to ensure the GNU Lesser General Public License
** requirements will be met: https://www.gnu.org/licenses/lgpl.html and
** http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html.
**
** As a special exception, The Qt Company gives you certain additional
** rights. These rights are described in The Qt Company LGPL Exception
** version 1.1, included in the file LGPL_EXCEPTION.txt in this package.
**
** $QT_END_LICENSE$
**
****************************************************************************/

#include "qnearfieldmanager_android_p.h"
#include "qnearfieldtarget_android_p.h"

#include "qndeffilter.h"
#include "qndefmessage.h"
#include "qndefrecord.h"
#include "qbytearray.h"
#include "qcoreapplication.h"
#include "qdebug.h"
#include "qlist.h"

#include <QtCore/QMetaType>
#include <QtCore/QMetaMethod>

QT_BEGIN_NAMESPACE

QNearFieldManagerPrivateImpl::QNearFieldManagerPrivateImpl() :
    m_detecting(false), m_handlerID(0)
{
    qRegisterMetaType<jobject>("jobject");
    qRegisterMetaType<QNdefMessage>("QNdefMessage");
    connect(this, SIGNAL(targetDetected(QNearFieldTarget*)), this, SLOT(handlerTargetDetected(QNearFieldTarget*)));
    connect(this, SIGNAL(targetLost(QNearFieldTarget*)), this, SLOT(handlerTargetLost(QNearFieldTarget*)));
}

QNearFieldManagerPrivateImpl::~QNearFieldManagerPrivateImpl()
{
}

void QNearFieldManagerPrivateImpl::handlerTargetDetected(QNearFieldTarget *target)
{
    if (ndefMessageHandlers.count() == 0 && ndefFilterHandlers.count() == 0) // if no handler is registered
        return;
    if (target->hasNdefMessage()) {
        connect(target, SIGNAL(ndefMessageRead(const QNdefMessage &, const QNearFieldTarget::RequestId &)),
                this, SLOT(handlerNdefMessageRead(const QNdefMessage &, const QNearFieldTarget::RequestId &)));
        connect(target, SIGNAL(requestCompleted(const QNearFieldTarget::RequestId &)),
                this, SLOT(handlerRequestCompleted(const QNearFieldTarget::RequestId &)));
        connect(target, SIGNAL(error(QNearFieldTarget::Error, const QNearFieldTarget::RequestId &)),
                this, SLOT(handlerError(QNearFieldTarget::Error, const QNearFieldTarget::RequestId &)));

        QNearFieldTarget::RequestId id = target->readNdefMessages();
        m_idToTarget.insert(id, target);
    }
}

void QNearFieldManagerPrivateImpl::handlerTargetLost(QNearFieldTarget *target)
{
    disconnect(target, SIGNAL(ndefMessageRead(const QNdefMessage &, const QNearFieldTarget::RequestId &)),
            this, SLOT(handlerNdefMessageRead(const QNdefMessage &, const QNearFieldTarget::RequestId &)));
    disconnect(target, SIGNAL(requestCompleted(const QNearFieldTarget::RequestId &)),
            this, SLOT(handlerRequestCompleted(const QNearFieldTarget::RequestId &)));
    disconnect(target, SIGNAL(error(QNearFieldTarget::Error, const QNearFieldTarget::RequestId &)),
            this, SLOT(handlerError(QNearFieldTarget::Error, const QNearFieldTarget::RequestId &)));
    m_idToTarget.remove(m_idToTarget.key(target));
}

void QNearFieldManagerPrivateImpl::handlerNdefMessageRead(const QNdefMessage &message, const QNearFieldTarget::RequestId &id)
{
    QNearFieldTarget *target = m_idToTarget.value(id);
    //For message handlers without filters
    for (int i = 0; i < ndefMessageHandlers.count(); i++) {
        ndefMessageHandlers.at(i).second.invoke(ndefMessageHandlers.at(i).first.second, Q_ARG(QNdefMessage, message), Q_ARG(QNearFieldTarget*, target));
    }

    //For message handlers that specified a filter
    for (int i = 0; i < ndefFilterHandlers.count(); ++i) {
        QNdefFilter filter = ndefFilterHandlers.at(i).second.first;
        if (filter.recordCount() > message.count())
            continue;

        int j;
        for (j = 0; j < filter.recordCount();) {
            if (message.at(j).typeNameFormat() != filter.recordAt(j).typeNameFormat
                    || message.at(j).type() != filter.recordAt(j).type ) {
                break;
            }
            ++j;
        }
        if (j == filter.recordCount())
            ndefFilterHandlers.at(i).second.second.invoke(ndefFilterHandlers.at(i).first.second, Q_ARG(QNdefMessage, message), Q_ARG(QNearFieldTarget*, target));
    }
}

void QNearFieldManagerPrivateImpl::handlerRequestCompleted(const QNearFieldTarget::RequestId &id)
{
    m_idToTarget.remove(id);
}

void QNearFieldManagerPrivateImpl::handlerError(QNearFieldTarget::Error error, const QNearFieldTarget::RequestId &id)
{
    Q_UNUSED(error);
    m_idToTarget.remove(id);
}

bool QNearFieldManagerPrivateImpl::isAvailable() const
{
    return AndroidNfc::isAvailable();
}

bool QNearFieldManagerPrivateImpl::startTargetDetection()
{
    if (m_detecting)
        return false;   // Already detecting targets

    m_detecting = true;
    updateReceiveState();
    return true;
}

void QNearFieldManagerPrivateImpl::stopTargetDetection()
{
    m_detecting = false;
    updateReceiveState();
}

int QNearFieldManagerPrivateImpl::registerNdefMessageHandler(QObject *object, const QMetaMethod &method)
{
    ndefMessageHandlers.append(QPair<QPair<int, QObject *>, QMetaMethod>(QPair<int, QObject *>(m_handlerID, object), method));
    updateReceiveState();
    //Returns the handler ID and increments it afterwards
    return m_handlerID++;
}

int QNearFieldManagerPrivateImpl::registerNdefMessageHandler(const QNdefFilter &filter,
                                                             QObject *object, const QMetaMethod &method)
{
    //If no record is set in the filter, we ignore the filter
    if (filter.recordCount()==0)
        return registerNdefMessageHandler(object, method);

    ndefFilterHandlers.append(QPair<QPair<int, QObject*>, QPair<QNdefFilter, QMetaMethod> >
                              (QPair<int, QObject*>(m_handlerID, object), QPair<QNdefFilter, QMetaMethod>(filter, method)));

    updateReceiveState();

    return m_handlerID++;
}

bool QNearFieldManagerPrivateImpl::unregisterNdefMessageHandler(int handlerId)
{
    for (int i=0; i<ndefMessageHandlers.count(); ++i) {
        if (ndefMessageHandlers.at(i).first.first == handlerId) {
            ndefMessageHandlers.removeAt(i);
            updateReceiveState();
            return true;
        }
    }
    for (int i=0; i<ndefFilterHandlers.count(); ++i) {
        if (ndefFilterHandlers.at(i).first.first == handlerId) {
            ndefFilterHandlers.removeAt(i);
            updateReceiveState();
            return true;
        }
    }
    return false;
}

void QNearFieldManagerPrivateImpl::requestAccess(QNearFieldManager::TargetAccessModes accessModes)
{
    Q_UNUSED(accessModes);
    //Do nothing, because we dont have access modes for the target
}

void QNearFieldManagerPrivateImpl::releaseAccess(QNearFieldManager::TargetAccessModes accessModes)
{
    Q_UNUSED(accessModes);
    //Do nothing, because we dont have access modes for the target
}

void QNearFieldManagerPrivateImpl::newIntent(jobject intent)
{
    // This function is called from different thread and is used to move intent to main thread.
    QMetaObject::invokeMethod(this, "onTargetDiscovered", Qt::QueuedConnection, Q_ARG(jobject, intent));
}

QByteArray QNearFieldManagerPrivateImpl::getUid(jobject intent)
{
    if (intent == 0)
        return QByteArray();

    AndroidNfc::AttachedJNIEnv aenv;
    JNIEnv *env = aenv.jniEnv;
    jobject tag = AndroidNfc::getTag(env, intent);

    jclass tagClass = env->GetObjectClass(tag);
    Q_ASSERT_X(tagClass != 0, "getUid", "could not get Tag class");

    jmethodID getIdMID = env->GetMethodID(tagClass, "getId", "()[B");
    Q_ASSERT_X(getIdMID != 0, "getUid", "could not get method ID for getId()");

    jbyteArray tagId = reinterpret_cast<jbyteArray>(env->CallObjectMethod(tag, getIdMID));
    Q_ASSERT_X(tagId != 0, "getUid", "getId() returned null object");

    QByteArray uid;
    jsize len = env->GetArrayLength(tagId);
    uid.resize(len);
    env->GetByteArrayRegion(tagId, 0, len, reinterpret_cast<jbyte*>(uid.data()));

    return uid;
}

void QNearFieldManagerPrivateImpl::onTargetDiscovered(jobject intent)
{
    AndroidNfc::AttachedJNIEnv aenv;
    JNIEnv *env = aenv.jniEnv;
    Q_ASSERT_X(env != 0, "onTargetDiscovered", "env pointer is null");

    // Getting tag object and UID
    jobject tag = AndroidNfc::getTag(env, intent);
    QByteArray uid = getUid(env, tag);

    // Accepting all targest but only sending signal of requested types.
    NearFieldTarget *&target = m_detectedTargets[uid];
    if (target) {
        target->setIntent(intent);  // Updating existing target
    } else {
        target = new NearFieldTarget(intent, uid, this);
        connect(target, SIGNAL(targetDestroyed(QByteArray)), this, SLOT(onTargetDestroyed(QByteArray)));
        connect(target, SIGNAL(targetLost(QNearFieldTarget*)), this, SIGNAL(targetLost(QNearFieldTarget*)));
    }
    emit targetDetected(target);
}

void QNearFieldManagerPrivateImpl::onTargetDestroyed(const QByteArray &uid)
{
    m_detectedTargets.remove(uid);
}

QByteArray QNearFieldManagerPrivateImpl::getUid(JNIEnv *env, jobject tag)
{
    jclass tagClass = env->GetObjectClass(tag);
    jmethodID getIdMID = env->GetMethodID(tagClass, "getId", "()[B");
    jbyteArray tagId = reinterpret_cast<jbyteArray>(env->CallObjectMethod(tag, getIdMID));
    QByteArray uid;
    jsize len = env->GetArrayLength(tagId);
    uid.resize(len);
    env->GetByteArrayRegion(tagId, 0, len, reinterpret_cast<jbyte*>(uid.data()));
    return uid;
}

void QNearFieldManagerPrivateImpl::updateReceiveState()
{
    if (m_detecting) {
        AndroidNfc::registerListener(this);
        AndroidNfc::startDiscovery();
    } else {
        if (ndefMessageHandlers.count() || ndefFilterHandlers.count()) {
            AndroidNfc::registerListener(this);
            AndroidNfc::startDiscovery();
        } else {
            AndroidNfc::stopDiscovery();
            AndroidNfc::unregisterListener(this);
        }
    }
}

QT_END_NAMESPACE
